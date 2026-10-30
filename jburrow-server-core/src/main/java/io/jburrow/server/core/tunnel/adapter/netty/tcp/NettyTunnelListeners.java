package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.control.TunnelListeners;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.http.SessionExchanges;
import io.jburrow.server.core.tunnel.application.ParkingBudget;
import io.jburrow.server.core.tunnel.application.PlannedTunnel;
import io.jburrow.server.core.tunnel.application.Registration;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.unix.Errors;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds a session's TCP tunnel listeners on the session's own event loop, and gives the session its streams: a
 * {@link StreamRelay} for the visitors they accept ({@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §TCP tunnels) and
 * {@link SessionExchanges} for the HTTP exchanges the edge hands it. An HTTP tunnel has no listener, since the edge
 * routes its requests by name: it takes its place in the result without a bind.
 *
 * <p>A port the pool chose that turns out to be in use on the host is quarantined and replaced, up to
 * {@link #MAX_BIND_ATTEMPTS} attempts in all; a port the client asked for fails with {@code PORT_UNAVAILABLE}. Neither
 * path releases a reservation: the session releases its registration once {@link #unbindAll} has completed.
 *
 * <p>Holds no session state of its own; each session's listeners live on its channel.
 */
public final class NettyTunnelListeners implements TunnelListeners {

    static final int MAX_BIND_ATTEMPTS = 3;

    /** Linux's {@code EADDRINUSE}: epoll reports a failed bind by its errno, not as a {@link BindException}. */
    private static final int EADDRINUSE = 98;

    private static final AttributeKey<List<TunnelListener>> LISTENERS =
            AttributeKey.valueOf(NettyTunnelListeners.class, "listeners");

    private static final Logger log = LoggerFactory.getLogger(NettyTunnelListeners.class);

    private final PortReservations ports;
    private final InetAddress bindAddress;
    private final RegistrationPolicy policy;
    private final int visitorReceiveBuffer;
    private final StreamQuota streamQuota;
    private final TunnelMetrics metrics;
    private final SessionExchanges.Settings exchanges;

    public NettyTunnelListeners(EdgeSettings settings, EngineDependencies deps) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(deps, "deps");
        this.ports = deps.ports();
        this.bindAddress = Cidr.parseAddress(deps.tcp().bindAddress())
                .orElseThrow(() -> new IllegalArgumentException("The tunnel bind address must be an IP literal: "
                        + deps.tcp().bindAddress()));
        this.policy = deps.registerTunnels().policy();
        this.visitorReceiveBuffer = deps.tcp().visitorReceiveBuffer();
        this.streamQuota = deps.streamQuota();
        this.metrics = deps.metrics();
        ParkingBudget parking =
                new ParkingBudget(settings.http().maxParked(), settings.http().maxParkedPerSession());
        metrics.bindParkedBytes(parking::parked);
        this.exchanges = new SessionExchanges.Settings(
                settings.http().responseTimeout(),
                settings.http().maxRequestBody(),
                settings.http().parkAfter(),
                parking,
                streamQuota,
                metrics);
    }

    @Override
    public Future<List<Tunnel>> bind(SessionContext session, Registration registration) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(registration, "registration");
        Channel channel = session.channel();
        StreamRelay relay = new StreamRelay(session, metrics, streamQuota);
        session.streams(new TunnelStreams(relay, SessionExchanges.attach(session, exchanges)));
        channel.attr(LISTENERS).set(new ArrayList<>());
        Promise<List<Tunnel>> bound = channel.eventLoop().newPromise();
        new Binding(session, relay, registration, bound).bindNext();
        return bound;
    }

    @Override
    public Future<?> unbindAll(SessionContext session) {
        EventLoop loop = session.channel().eventLoop();
        List<TunnelListener> listeners = session.channel().attr(LISTENERS).getAndSet(null);
        Promise<Void> unbound = loop.newPromise();
        if (listeners == null || listeners.isEmpty()) {
            return unbound.setSuccess(null);
        }
        PromiseCombiner closes = new PromiseCombiner(loop);
        for (TunnelListener listener : listeners) {
            closes.add(listener.close());
        }
        closes.finish(unbound);
        return unbound;
    }

    /** Whether a bind failed because something else holds the port. */
    static boolean addressInUse(Throwable cause) {
        return cause instanceof BindException
                || cause instanceof Errors.NativeIoException failure && failure.expectedErr() == -EADDRINUSE;
    }

    /** One registration's binds, one planned tunnel after the other, all on the session's event loop. */
    private final class Binding {

        private final SessionContext session;
        private final StreamRelay relay;
        private final Registration registration;
        private final Promise<List<Tunnel>> bound;
        private final List<PlannedTunnel> planned;
        private final List<Tunnel> tunnels = new ArrayList<>();
        private int attempt = 1;

        Binding(SessionContext session, StreamRelay relay, Registration registration, Promise<List<Tunnel>> bound) {
            this.session = session;
            this.relay = relay;
            this.registration = registration;
            this.bound = bound;
            this.planned = new ArrayList<>(registration.tunnels());
        }

        void bindNext() {
            while (tunnels.size() < planned.size() && planned.get(tunnels.size()) instanceof PlannedTunnel.Http http) {
                tunnels.add(http.toTunnel(policy));
            }
            if (tunnels.size() == planned.size()) {
                bound.trySuccess(List.copyOf(tunnels));
                return;
            }
            PlannedTunnel.Tcp next = (PlannedTunnel.Tcp) planned.get(tunnels.size());
            Tunnel tunnel = next.toTunnel(policy);
            EventLoop loop = session.channel().eventLoop();
            TunnelListener listener = TunnelListener.bind(
                    loop,
                    new InetSocketAddress(bindAddress, next.reservation().port()),
                    visitorReceiveBuffer,
                    new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel visitor) {
                            visitor.pipeline().addLast(new VisitorHandler(relay, tunnel));
                        }
                    });
            listeners().add(listener);
            listener.bindFuture().addListener((ChannelFuture done) -> onBound(done, next, tunnel, listener));
        }

        /**
         * If the session closed while binding, its teardown has already closed this listener with the others, and
         * releases the registration as it stands.
         */
        private void onBound(ChannelFuture done, PlannedTunnel.Tcp tunnel, Tunnel bound, TunnelListener listener) {
            if (!session.session().isRegistering()) {
                this.bound.tryFailure(new IllegalStateException("The session closed while its tunnels were binding"));
                return;
            }
            if (done.isSuccess()) {
                tunnels.add(bound);
                attempt = 1;
                bindNext();
                return;
            }
            listeners().remove(listener);
            Throwable cause = done.cause();
            String ref = tunnel.request().ref();
            if (!addressInUse(cause)) {
                this.bound.tryFailure(cause);
            } else if (tunnel.request().remotePort() != null) {
                this.bound.tryFailure(ProtocolException.session(
                                ErrorCode.PORT_UNAVAILABLE,
                                "Port " + tunnel.reservation().port() + " is in use on the server.")
                        .forTunnel(ref));
            } else {
                retryElsewhere(tunnel);
            }
        }

        private void retryElsewhere(PlannedTunnel.Tcp tunnel) {
            Reservation inUse = tunnel.reservation();
            ports.quarantine(inUse);
            log.warn("tunnel port {} is in use on the host; quarantined (attempt {})", inUse.port(), attempt);
            if (attempt >= MAX_BIND_ATTEMPTS) {
                bound.tryFailure(ProtocolException.session(
                                ErrorCode.PORT_POOL_EXHAUSTED, "The server could not find a free port.")
                        .forTunnel(tunnel.request().ref()));
                return;
            }
            Reservation replacement;
            try {
                replacement = ports.reserve(OptionalInt.empty());
            } catch (ProtocolException refused) {
                bound.tryFailure(refused.forTunnel(tunnel.request().ref()));
                return;
            }
            attempt++;
            planned.set(tunnels.size(), new PlannedTunnel.Tcp(tunnel.request(), replacement, tunnel.id()));
            session.registration(new Registration(registration.principal(), planned));
            bindNext();
        }

        private List<TunnelListener> listeners() {
            return session.channel().attr(LISTENERS).get();
        }
    }
}
