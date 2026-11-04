package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.adapter.netty.FrameCodec;
import io.jburrow.protocol.adapter.netty.WebSocketFrameDecoder;
import io.jburrow.protocol.adapter.netty.WebSocketFrameEncoder;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.SessionInitializer;
import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an upgraded connection into a control session: adds the protocol codec, {@link LivenessHandler} and
 * {@link ControlSessionHandler} after the edge's message aggregator, with a new {@link Session} behind them. Each
 * session has its own codec, which decodes what {@code http} and {@code resume} introduce once the session has
 * negotiated them.
 *
 * <p>Drains and ends the sessions when the server stops, each on its own event loop
 * ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain).
 */
public final class ControlSessionInitializer implements SessionInitializer {

    private static final Logger log = LoggerFactory.getLogger(ControlSessionInitializer.class);

    /** What {@link #drainSession} returns for a session that wasn't established, so it had nothing to drain. */
    private static final int NOT_DRAINED = -1;

    static final String SERVER_NAME = "jburrow-server";

    static final String FRAME_DECODER = "frame-decoder";
    static final String FRAME_ENCODER = "frame-encoder";
    static final String FRAME_COUNTER = "frame-counter";
    static final String LIVENESS = "liveness";
    static final String SESSION = "session";

    private final EdgeSettings settings;
    private final EngineDependencies deps;
    private final TunnelListeners tunnelListeners;
    private final SessionRegistry registry;
    private final SessionCloser closer;
    private final ControlJson json = ControlJson.create();
    private final FrameCounter frameCounter;
    private final ServerInfo serverInfo;
    private final SessionLimits limits;
    private final LongSupplier nanoTime = System::nanoTime;

    /** Set once the drain begins: a session that starts later ends at once. */
    private volatile boolean stopping;

    /** Every session live since the drain began, whose release {@link #end} waits for. */
    private final Set<SessionContext> ending = ConcurrentHashMap.newKeySet();

    public ControlSessionInitializer(EdgeSettings settings, EngineDependencies deps, TunnelListeners tunnelListeners) {
        this(settings, deps, new SessionRegistry(), tunnelListeners);
    }

    /** With a {@code registry} the composition root shares, so the live sessions can be found from outside the edge. */
    public ControlSessionInitializer(
            EdgeSettings settings, EngineDependencies deps, SessionRegistry registry, TunnelListeners tunnelListeners) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.deps = Objects.requireNonNull(deps, "deps");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tunnelListeners = Objects.requireNonNull(tunnelListeners, "tunnelListeners");
        this.closer = new SessionCloser(deps.metrics());
        this.frameCounter = new FrameCounter(deps.metrics());
        this.serverInfo = new ServerInfo(SERVER_NAME, settings.serverVersion());
        this.limits = new SessionLimits(
                FrameLimits.MAX_DATA_PAYLOAD,
                deps.registerTunnels().policy().maxTunnels(),
                settings.maxConcurrentStreams(),
                settings.pingInterval().toMillis(),
                settings.idleTimeout().toMillis());
    }

    @Override
    public void initialize(Channel channel, String subprotocol, InetAddress clientAddress, HandshakeTicket ticket) {
        if (!channel.isActive()) {
            return;
        }
        Session session = new Session(
                SessionId.random(), settings.maxConcurrentStreams(), settings.windows(), settings.capabilities());
        FrameCodec codec = new FrameCodec(FrameCodec.Role.SERVER, json, session::capabilities);
        SessionContext context =
                new SessionContext(session, channel, ticket, clientAddress, nanoTime.getAsLong(), closer);
        LivenessHandler liveness =
                new LivenessHandler(context, closer, settings.pingInterval(), settings.idleTimeout(), nanoTime);
        context.liveness(liveness);
        ControlSessionHandler handler = new ControlSessionHandler(
                context,
                deps.registerTunnels(),
                deps.blockingExecutor(),
                tunnelListeners,
                registry,
                closer,
                deps.metrics(),
                serverInfo,
                limits,
                settings.windows(),
                settings.handshakeTimeout());
        channel.pipeline()
                .addLast(FRAME_DECODER, new WebSocketFrameDecoder(codec))
                .addLast(FRAME_ENCODER, new WebSocketFrameEncoder(codec))
                .addLast(FRAME_COUNTER, frameCounter)
                .addLast(LIVENESS, liveness)
                .addLast(SESSION, handler);
        // The session is in the registry by now, so a drain that begins after this check finds it there.
        if (stopping) {
            ending.add(context);
            closer.fail(context, shuttingDown());
        }
    }

    @Override
    public CompletableFuture<Drained> drain(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        stopping = true;
        List<CompletableFuture<Integer>> started = new ArrayList<>();
        List<CompletableFuture<Void>> drained = new ArrayList<>();
        for (SessionContext context : registry.all()) {
            ending.add(context);
            CompletableFuture<Integer> openStreams = new CompletableFuture<>();
            CompletableFuture<Void> idle = new CompletableFuture<>();
            started.add(openStreams);
            drained.add(idle);
            onLoop(context, () -> openStreams.complete(drainSession(context, timeout, idle)), () -> {
                openStreams.complete(NOT_DRAINED);
                idle.complete(null);
            });
        }
        return CompletableFuture.allOf(drained.toArray(CompletableFuture[]::new))
                .completeOnTimeout(null, timeout.toNanos(), TimeUnit.NANOSECONDS)
                .thenApply(done -> tally(started));
    }

    /**
     * On the session's loop: a session in its handshake ends now; an established one drains, and {@code idle}
     * completes when it has no open stream.
     *
     * @return the streams the session had open, or {@link #NOT_DRAINED}
     */
    private int drainSession(SessionContext context, Duration timeout, CompletableFuture<Void> idle) {
        Session session = context.session();
        switch (session.state()) {
            case AWAITING_HELLO, AWAITING_REGISTER, REGISTERING -> {
                idle.complete(null);
                closer.fail(context, shuttingDown());
                return NOT_DRAINED;
            }
            case DRAINING, CLOSING -> {
                idle.complete(null);
                return NOT_DRAINED;
            }
            case ESTABLISHED -> {}
        }
        int openStreams = session.openStreamIds().size();
        EventLoop loop = context.channel().eventLoop();
        session.onDraining(() -> completeLater(loop, idle));
        tunnelListeners.stopAccepting(context);
        if (session.resumeEnabled()) {
            long reconnectAfter = ThreadLocalRandom.current()
                    .nextLong(settings.drain().reconnectSpread().toMillis() + 1);
            context.channel().writeAndFlush(new Frame.GoAway(new GoAway(reconnectAfter, timeout.toMillis())));
        }
        log.debug(
                "session {}: draining with {} open streams{}",
                session.id(),
                openStreams,
                session.resumeEnabled() ? ", GOAWAY sent" : "");
        return openStreams;
    }

    /**
     * Completes {@code idle} in a task of its own, never inside the session's call that found it idle, so whatever
     * waits on the drain runs outside the session's own code.
     */
    private static void completeLater(EventLoop loop, CompletableFuture<Void> idle) {
        try {
            loop.execute(() -> idle.complete(null));
        } catch (RejectedExecutionException e) {
            idle.complete(null);
        }
    }

    private static Drained tally(List<CompletableFuture<Integer>> started) {
        int sessions = 0;
        int openStreams = 0;
        for (CompletableFuture<Integer> session : started) {
            int streams = session.getNow(NOT_DRAINED);
            if (streams != NOT_DRAINED) {
                sessions++;
                openStreams += streams;
            }
        }
        return new Drained(sessions, openStreams);
    }

    /**
     * Sends every session {@code ERROR SERVER_SHUTTING_DOWN} after resetting its open streams, each on its own event
     * loop, and waits for each session live since the drain began to give back what it took, so its names and ports
     * are on hold.
     */
    @Override
    public CompletableFuture<Integer> end() {
        stopping = true;
        List<CompletableFuture<Integer>> cut = new ArrayList<>();
        for (SessionContext context : registry.all()) {
            ending.add(context);
            CompletableFuture<Integer> streams = new CompletableFuture<>();
            cut.add(streams);
            onLoop(
                    context,
                    () -> {
                        Session session = context.session();
                        int open = session.state() == SessionState.CLOSING
                                ? 0
                                : session.openStreamIds().size();
                        closer.fail(context, shuttingDown());
                        streams.complete(open);
                    },
                    () -> streams.complete(0));
        }
        Stream<CompletableFuture<?>> waits =
                Stream.concat(cut.stream(), ending.stream().map(SessionContext::released));
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                .thenApply(
                        done -> cut.stream().mapToInt(CompletableFuture::join).sum());
    }

    /**
     * Runs {@code task} on the session's loop. {@code otherwise} runs instead if the loop has shut down, and if the task
     * throws, which then ends the session as an internal error, so whoever waits on the task is never left waiting.
     */
    private static void onLoop(SessionContext context, Runnable task, Runnable otherwise) {
        try {
            context.channel().eventLoop().execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    otherwise.run();
                    context.closer().failInternally(context, e);
                }
            });
        } catch (RejectedExecutionException e) {
            otherwise.run();
        }
    }

    private static ProtocolException shuttingDown() {
        return ProtocolException.session(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down.");
    }

    /** The live sessions. */
    public SessionRegistry registry() {
        return registry;
    }
}
