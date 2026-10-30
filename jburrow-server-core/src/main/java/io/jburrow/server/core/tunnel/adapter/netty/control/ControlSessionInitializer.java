package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.adapter.netty.FrameCodec;
import io.jburrow.protocol.adapter.netty.WebSocketFrameDecoder;
import io.jburrow.protocol.adapter.netty.WebSocketFrameEncoder;
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
import io.netty.channel.Channel;
import java.net.InetAddress;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Turns an upgraded connection into a control session: adds the protocol codec, {@link LivenessHandler} and
 * {@link ControlSessionHandler} after the edge's message aggregator, with a new {@link Session} behind them. Each
 * session has its own codec, which decodes what {@code http} introduces once the session has negotiated it.
 */
public final class ControlSessionInitializer implements SessionInitializer {

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
        FrameCodec codec = new FrameCodec(FrameCodec.Role.SERVER, json, session::httpEnabled);
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
    }

    /** Sends every session {@code ERROR SERVER_SHUTTING_DOWN}, each on its own event loop, without waiting. */
    @Override
    public void shutDown() {
        ProtocolException shuttingDown =
                ProtocolException.session(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down.");
        for (SessionContext context : registry.all()) {
            context.channel().eventLoop().execute(() -> closer.fail(context, shuttingDown));
        }
    }

    /** The live sessions. */
    public SessionRegistry registry() {
        return registry;
    }
}
