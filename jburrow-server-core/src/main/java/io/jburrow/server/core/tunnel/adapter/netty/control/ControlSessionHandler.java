package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.Registration;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.SessionEnd;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one control session: reacts to the client's frames by calling the {@link Session} domain object, and to its
 * decisions by doing I/O. Registration blocks, so it runs on the blocking executor and its result comes back to the
 * event loop, where the session's state is checked again before anything is used.
 *
 * <p>Every way the session ends goes through the connection closing, and {@link #channelInactive} gives back
 * everything the session took, whatever state it reached.
 *
 * <p>Releases frames itself rather than through the superclass, because a {@code DATA} frame's buffer is handed on to
 * the stream it belongs to rather than released here once streams are relayed.
 */
final class ControlSessionHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(ControlSessionHandler.class);

    /** What a registration on the blocking executor produced, other than a {@link Registration} or a refusal. */
    private record UnexpectedFailure(Throwable cause) {}

    private final SessionContext context;
    private final Session session;
    private final RegisterTunnels registerTunnels;
    private final Executor blockingExecutor;
    private final TunnelListeners tunnelListeners;
    private final SessionRegistry registry;
    private final SessionCloser closer;
    private final TunnelMetrics metrics;
    private final ServerInfo serverInfo;
    private final SessionLimits limits;
    private final Duration handshakeTimeout;

    private boolean tornDown;

    ControlSessionHandler(
            SessionContext context,
            RegisterTunnels registerTunnels,
            Executor blockingExecutor,
            TunnelListeners tunnelListeners,
            SessionRegistry registry,
            SessionCloser closer,
            TunnelMetrics metrics,
            ServerInfo serverInfo,
            SessionLimits limits,
            Duration handshakeTimeout) {
        super(Object.class, false);
        this.context = Objects.requireNonNull(context, "context");
        this.session = context.session();
        this.registerTunnels = Objects.requireNonNull(registerTunnels, "registerTunnels");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.tunnelListeners = Objects.requireNonNull(tunnelListeners, "tunnelListeners");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.serverInfo = Objects.requireNonNull(serverInfo, "serverInfo");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
    }

    /** The session begins: it is registered and counted, and its handshake deadline starts. */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        registry.add(context);
        metrics.sessionStarted();
        context.handshakeDeadline(
                ctx.executor().schedule(this::onHandshakeDeadline, handshakeTimeout.toNanos(), TimeUnit.NANOSECONDS));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        switch (msg) {
            case Frame frame -> onFrame(frame);
            case CloseWebSocketFrame close -> {
                int status = close.statusCode();
                close.release();
                closer.peerClosed(context, status);
            }
            default -> ReferenceCountUtil.release(msg);
        }
    }

    private void onFrame(Frame frame) {
        boolean handedOn = false;
        try {
            if (session.state() == SessionState.CLOSING) {
                return;
            }
            session.checkInbound(frame.type());
            switch (frame) {
                case Frame.Hello hello -> onHello(hello.message());
                case Frame.Register register -> onRegister(register.message());
                case Frame.Data data -> {
                    handedOn = true;
                    onData(data);
                }
                case Frame.Close close -> onClose(close);
                case Frame.Error error -> closer.peerFailed(context, error.body());
                default -> throw new IllegalStateException(frame.type() + " reached the session handler");
            }
        } finally {
            if (!handedOn) {
                frame.release();
            }
        }
    }

    /** Takes over the frame's buffer. */
    private void onData(Frame.Data data) {
        Optional<SessionStreams> streams = context.streams();
        if (streams.isPresent()) {
            streams.get().onData(data);
            return;
        }
        try {
            onStreamFrame(data.streamId(), StreamEvent.DATA_RECEIVED);
        } finally {
            data.release();
        }
    }

    private void onClose(Frame.Close close) {
        Optional<SessionStreams> streams = context.streams();
        if (streams.isPresent()) {
            streams.get().onClose(close);
        } else {
            onStreamFrame(
                    close.streamId(),
                    close.reset().isPresent() ? StreamEvent.RST_RECEIVED : StreamEvent.CLOSE_RECEIVED);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        context.streams().ifPresent(SessionStreams::onReadComplete);
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        boolean writable = ctx.channel().isWritable();
        context.streams().ifPresent(streams -> streams.onSessionWritabilityChanged(writable));
        ctx.fireChannelWritabilityChanged();
    }

    private void onHello(Hello hello) {
        session.onHello(hello);
        context.channel()
                .writeAndFlush(
                        new Frame.ServerHello(new ServerHello(session.id().value(), serverInfo, List.of(), limits)));
        context.liveness().start();
    }

    private void onRegister(Register register) {
        session.onRegisterReceived();
        EventLoop loop = context.channel().eventLoop();
        long started = System.nanoTime();
        try {
            blockingExecutor.execute(() -> {
                Object outcome;
                try {
                    outcome = registerTunnels.execute(context.ticket(), register);
                } catch (ProtocolException refused) {
                    outcome = refused;
                } catch (Throwable unexpected) {
                    outcome = new UnexpectedFailure(unexpected);
                }
                Object result = outcome;
                deliver(loop, result, () -> onRegistrationOutcome(result, started));
            });
        } catch (RejectedExecutionException e) {
            throw ProtocolException.session(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down.");
        }
    }

    /**
     * Hands a registration's result to the event loop. If the loop has shut down, the session is gone with it, and a
     * registration that reserved ports releases them here.
     */
    private void deliver(EventLoop loop, Object result, Runnable onLoop) {
        try {
            loop.execute(onLoop);
        } catch (RejectedExecutionException e) {
            if (result instanceof Registration registration) {
                registerTunnels.release(registration);
            }
        }
    }

    private void onRegistrationOutcome(Object outcome, long started) {
        if (!session.isRegistering()) {
            if (outcome instanceof Registration registration) {
                registerTunnels.release(registration);
            }
            return;
        }
        long elapsed = System.nanoTime() - started;
        try {
            switch (outcome) {
                case Registration registration -> bind(registration, started);
                case ProtocolException refused -> {
                    metrics.registerFailed(elapsed, refused.code());
                    closer.fail(context, refused);
                }
                case UnexpectedFailure failure -> {
                    metrics.registerFailed(elapsed, ErrorCode.INTERNAL_ERROR);
                    closer.failInternally(context, failure.cause());
                }
                default -> throw new IllegalStateException("Unknown registration outcome " + outcome);
            }
        } catch (RuntimeException e) {
            closer.failInternally(context, e);
        }
    }

    private void bind(Registration registration, long started) {
        context.registration(registration);
        Future<List<Tunnel>> bound = tunnelListeners.bind(context, registration);
        bound.addListener(done -> {
            EventLoop loop = context.channel().eventLoop();
            if (loop.inEventLoop()) {
                onBound(bound, registration.principal(), started);
            } else {
                loop.execute(() -> onBound(bound, registration.principal(), started));
            }
        });
    }

    /** If the session closed while binding, its teardown is already unbinding and releasing. */
    private void onBound(Future<List<Tunnel>> bound, Principal principal, long started) {
        if (!session.isRegistering()) {
            return;
        }
        long elapsed = System.nanoTime() - started;
        try {
            if (!bound.isSuccess()) {
                if (bound.cause() instanceof ProtocolException refused) {
                    metrics.registerFailed(elapsed, refused.code());
                    closer.fail(context, refused);
                } else {
                    metrics.registerFailed(elapsed, ErrorCode.INTERNAL_ERROR);
                    closer.failInternally(context, bound.cause());
                }
                return;
            }
            List<Tunnel> tunnels = bound.getNow();
            session.onEstablished(principal, tunnels);
            context.channel()
                    .writeAndFlush(new Frame.Registered(new Registered(
                            tunnels.stream().map(Tunnel::toRegistered).toList())));
            context.cancelHandshakeDeadline();
            metrics.handshakeEstablished();
            metrics.registerSucceeded(elapsed);
            logEstablished(principal, tunnels);
        } catch (RuntimeException e) {
            closer.failInternally(context, e);
        }
    }

    /**
     * A {@code DATA} or {@code CLOSE} from the client on a session whose tunnel listeners provide no streams. No stream
     * is opened without a listener to accept a visitor, so every such frame is for a stream that was never opened.
     */
    private void onStreamFrame(int streamId, StreamEvent event) {
        StreamOutcome outcome = session.onStreamFrame(streamId, event);
        if (!(outcome instanceof StreamOutcome.Discard)) {
            throw new IllegalStateException("Stream " + streamId + " is open but has no relay");
        }
    }

    private void onHandshakeDeadline() {
        if (session.state() != SessionState.ESTABLISHED) {
            closer.fail(
                    context,
                    ProtocolException.session(
                            ErrorCode.HANDSHAKE_TIMEOUT,
                            "The session wasn't established within " + handshakeTimeout.toMillis() + " ms."));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        switch (cause) {
            case ProtocolException error -> closer.fail(context, error);
            case CorruptedWebSocketFrameException violation ->
                closer.abort(
                        context,
                        violation.closeStatus() == WebSocketCloseStatus.MESSAGE_TOO_BIG
                                ? ErrorCode.FRAME_SIZE_ERROR
                                : ErrorCode.PROTOCOL_ERROR);
            case TooLongFrameException tooLong ->
                closer.fail(
                        context,
                        ProtocolException.session(
                                ErrorCode.FRAME_SIZE_ERROR,
                                "A message is longer than " + FrameLimits.MAX_MESSAGE_LENGTH + " bytes."));
            case IOException lost -> {
                log.debug("session {}: connection failed: {}", session.id(), lost.toString());
                closer.close(context, SessionEnd.CONNECTION_LOST);
            }
            default -> closer.failInternally(context, cause);
        }
    }

    /** Teardown, however the session ended: nothing the session took outlives its connection. */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        tearDown();
        ctx.fireChannelInactive();
    }

    private void tearDown() {
        if (tornDown) {
            return;
        }
        tornDown = true;
        if (session.state() != SessionState.CLOSING) {
            session.onClosing();
            context.end(SessionEnd.CONNECTION_LOST);
        }
        context.cancelHandshakeDeadline();
        context.cancelForceClose();
        cancelStreams();
        unbindThenRelease();
        registry.remove(context);
        String reason = session.closeReason()
                .map(code -> {
                    metrics.sessionEnded(code);
                    return code.code();
                })
                .orElseGet(() -> {
                    SessionEnd end = context.end().orElse(SessionEnd.CONNECTION_LOST);
                    metrics.sessionEnded(end);
                    return end.name().toLowerCase(Locale.ROOT);
                });
        log.info(
                "session closed: session={} principal={} reason={} duration={}ms streams={} bytesIn={} bytesOut={}",
                session.id(),
                session.principal().map(Principal::name).orElse("-"),
                reason,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.startedNanos()),
                context.streamsServed(),
                context.bytesIn(),
                context.bytesOut());
    }

    /** A failure here must not keep the listeners bound, so it is logged and teardown goes on. */
    private void cancelStreams() {
        try {
            context.streams().ifPresent(SessionStreams::cancelAll);
        } catch (RuntimeException e) {
            log.error("session {}: could not reset its streams", session.id(), e);
        }
    }

    /** Ports go back to the pool only once every listener on them is closed. */
    private void unbindThenRelease() {
        Future<?> unbound;
        try {
            unbound = tunnelListeners.unbindAll(context);
        } catch (RuntimeException e) {
            log.error("session {}: could not unbind its tunnels; their ports stay reserved", session.id(), e);
            return;
        }
        unbound.addListener(done -> {
            if (done.isSuccess()) {
                context.takeRegistration().ifPresent(registerTunnels::release);
            } else {
                log.error(
                        "session {}: could not unbind its tunnels; their ports stay reserved",
                        session.id(),
                        done.cause());
            }
        });
    }

    private void logEstablished(Principal principal, List<Tunnel> tunnels) {
        ClientInfo client = session.client().orElseThrow();
        log.info(
                "session established: session={} principal={} client={}/{} address={} tunnels={}",
                session.id(),
                principal.name(),
                client.name(),
                client.version(),
                context.clientAddress().getHostAddress(),
                tunnels.stream()
                        .map(tunnel -> tunnel.kind().wireName() + ":" + tunnel.publicPort())
                        .collect(Collectors.joining(",", "[", "]")));
    }
}
