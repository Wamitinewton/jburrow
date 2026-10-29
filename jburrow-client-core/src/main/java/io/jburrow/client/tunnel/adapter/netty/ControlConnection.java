package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ActiveTunnel;
import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One session's connection to the server, from the TCP connect to the close. It owns the {@link ClientSession},
 * completes {@code start()} and {@link #closed()}, and is the one place a session ends, so every path ends it the same
 * way ({@code docs/protocol.md} §8.6):
 *
 * <ul>
 *   <li>this client finds a session error: streams reset with {@code CANCELLED}, {@code ERROR}, then a close frame with
 *       the code's close status;
 *   <li>the server sends {@code ERROR}: nothing more is sent, and the server's close frame is answered;
 *   <li>the user closes: streams reset with {@code CANCELLED}, then a close frame with status {@code 1000};
 *   <li>the connection fails: it is closed.
 * </ul>
 *
 * <p>Whoever starts the WebSocket close waits at most {@link #CLOSE_TIMEOUT} for the other side's close frame. The first
 * reason recorded is the one reported. A session that never establishes fails {@code start()} at once; one that did
 * completes {@link #closed()} when its connection has closed and its streams are gone.
 *
 * <p>Everything except the {@link TunnelHandle} methods and {@link #awaitStart()} runs on the session's event loop.
 */
final class ControlConnection implements TunnelHandle {

    /** How long the side that sent a close frame waits for the other side's before cutting the connection. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(ControlConnection.class);

    private static final String INTERNAL_ERROR_MESSAGE = "The client failed unexpectedly.";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final ClientSettings settings;
    private final ClientSession session;
    private final CompletableFuture<TunnelHandle> started = new CompletableFuture<>();
    private final CompletableFuture<TunnelClosed> closed = new CompletableFuture<>();
    private final AtomicBoolean endReported = new AtomicBoolean();

    private Channel channel;
    private LocalConnector streams;
    private ScheduledFuture<?> handshakeDeadline;
    private ScheduledFuture<?> forceClose;
    private boolean upgraded;
    private boolean closeFrameSent;
    private boolean tornDown;
    private TunnelClosed closeReason;

    private volatile Channel published;
    private volatile String sessionId;
    private volatile List<RegisteredTunnel> tunnels = List.of();

    ControlConnection(ClientSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.session = new ClientSession(settings.tunnels(), settings.windows());
    }

    ClientSession session() {
        return session;
    }

    // The handle, from any thread.

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public List<RegisteredTunnel> tunnels() {
        return tunnels;
    }

    @Override
    public CompletionStage<TunnelClosed> closed() {
        return closed.minimalCompletionStage();
    }

    @Override
    public void close() {
        Channel current = published;
        if (current == null) {
            return;
        }
        try {
            current.eventLoop().execute(this::closeByUser);
        } catch (RejectedExecutionException e) {
            log.debug("close requested after the client stopped");
        }
    }

    /**
     * Blocks until the session is established or fails.
     *
     * @throws TunnelStartException why it failed
     */
    TunnelHandle awaitStart() throws TunnelStartException {
        try {
            return started.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TunnelStartException failure) {
                throw failure;
            }
            throw new IllegalStateException("start() failed unexpectedly", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new TunnelStartException(Kind.CONNECT_FAILED, "Interrupted while the session was being set up.");
        }
    }

    /** Waits up to {@code timeout} for an established session to finish closing. */
    void awaitClosed(Duration timeout) {
        if (!started.isDone() || started.isCompletedExceptionally()) {
            return;
        }
        try {
            closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.debug("the session didn't finish closing within {}", timeout);
        }
    }

    /**
     * Completes whatever is still pending once the event loop has stopped, so {@code start()} returns and
     * {@link #closed()} completes even if the loop went away first.
     */
    void abandon() {
        started.completeExceptionally(
                new TunnelStartException(Kind.CONNECT_FAILED, "The client was closed before the session was set up."));
        if (!started.isCompletedExceptionally()) {
            reportEnd(closeReason != null ? closeReason : TunnelClosed.userClosed());
        }
    }

    // The session's life, on its event loop.

    /** The TCP connection is up: the handshake deadline starts, covering TLS, the upgrade, and the handshake. */
    void onConnected(Channel channel, LocalConnector streams) {
        this.channel = channel;
        this.streams = streams;
        this.published = channel;
        Duration timeout = settings.handshakeTimeout();
        handshakeDeadline =
                channel.eventLoop().schedule(this::onHandshakeDeadline, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    boolean isUpgraded() {
        return upgraded;
    }

    /** The WebSocket upgrade completed: send {@code HELLO} and {@code REGISTER} back to back, in one flush (§8.3). */
    void onUpgraded(String subprotocol) {
        if (session.isClosing()) {
            return;
        }
        upgraded = true;
        if (!ProtocolVersion.CURRENT.equals(subprotocol)) {
            fail(ProtocolException.session(
                    ErrorCode.PROTOCOL_ERROR, "The server selected a subprotocol this client didn't offer."));
            return;
        }
        session.onUpgraded();
        channel.write(new Frame.Hello(new Hello(settings.clientInfo(), List.of(), session.windows())));
        channel.write(new Frame.Register(session.register(settings.authtoken())));
        channel.flush();
    }

    /** The server's {@code REGISTER} reply arrived. */
    void onEstablished(List<ActiveTunnel> active) {
        cancel(handshakeDeadline);
        sessionId = session.sessionId().orElseThrow().toString();
        tunnels = active.stream().map(ActiveTunnel::registered).toList();
        log.debug(
                "session established: session={} tunnels={}",
                sessionId,
                tunnels.stream().map(RegisteredTunnel::url).toList());
        tell(events -> events.onEstablished(sessionId, tunnels));
        started.complete(this);
    }

    /** {@code start()} can't succeed: nothing is sent, and the connection is closed. */
    void startFailed(TunnelStartException failure) {
        if (beginClosing(TunnelClosed.connectionLost(failure.getMessage()), failure)) {
            log.debug("session failed to start: {} {}", failure.kind(), failure.getMessage());
        }
        if (channel != null) {
            channel.close();
        }
    }

    /**
     * This client found a session error ({@code docs/protocol.md} §2.3): reset every stream with {@code CANCELLED}, send
     * {@code ERROR}, then the close frame with the code's close status. A stream-scope error reaching here is a bug and
     * is sent as {@code INTERNAL_ERROR}.
     */
    void fail(ProtocolException error) {
        ProtocolException sent = error.scope() == ErrorScope.SESSION ? error : internalError();
        String message = truncate(sent.getMessage());
        Kind kind = sent.code() == ErrorCode.HEARTBEAT_TIMEOUT ? Kind.TIMEOUT : Kind.LOCAL_ERROR;
        if (!beginClosing(
                TunnelClosed.localError(sent.code(), message),
                new TunnelStartException(kind, message, Optional.of(sent.code()), List.of(), null))) {
            return;
        }
        log.debug("session error: session={} code={} message=\"{}\"", describeSession(), sent.code(), message);
        if (!channel.isActive()) {
            channel.close();
            return;
        }
        streams.cancelAll(true);
        channel.write(new Frame.Error(new ErrorBody(sent.code(), message, null)));
        sendCloseFrame(sent.code().closeCode().orElseThrow(), sent.code().code());
    }

    /** {@link #fail} with {@code INTERNAL_ERROR}, after logging {@code cause}. */
    void failInternally(Throwable cause) {
        if (!session.isClosing()) {
            log.error("Internal error in session {}", describeSession(), cause);
        }
        fail(internalError());
    }

    /**
     * A WebSocket violation by the server, which Netty's decoder has already answered with its own close frame: the
     * connection can't carry an {@code ERROR} any more, so it is closed.
     */
    void abort(ErrorCode code, String message) {
        if (beginClosing(
                TunnelClosed.localError(code, message),
                new TunnelStartException(Kind.LOCAL_ERROR, message, Optional.of(code), List.of(), null))) {
            log.debug("session error: session={} code={} (WebSocket violation)", describeSession(), code);
        }
        channel.close();
    }

    /** The server sent {@code ERROR}: send nothing more, and wait for its close frame to answer it (§8.6). */
    void peerFailed(ErrorBody error) {
        String message = PeerText.sanitize(error.message());
        if (!beginClosing(
                TunnelClosed.serverError(error.code(), message),
                new TunnelStartException(Kind.SERVER_ERROR, message, Optional.of(error.code()), List.of(), null))) {
            return;
        }
        log.debug("server error: session={} code={} message=\"{}\"", describeSession(), error.code(), message);
        streams.cancelAll(false);
        scheduleForceClose();
    }

    /**
     * The server sent a WebSocket close frame. If this client sent its own already, the close is complete. Otherwise
     * the frame is answered, and a close without an {@code ERROR} first counts as a lost connection.
     */
    void peerClosed(int status) {
        if (closeFrameSent) {
            channel.close();
            return;
        }
        String message = "The server closed the connection" + (status < 0 ? "" : " with WebSocket status " + status)
                + (session.isClosing() ? "." : " without an ERROR.");
        if (beginClosing(TunnelClosed.connectionLost(message), connectFailed(message))) {
            streams.cancelAll(false);
        }
        closeFrameSent = true;
        CloseWebSocketFrame answer = status < 0 ? new CloseWebSocketFrame() : new CloseWebSocketFrame(status, "");
        channel.writeAndFlush(answer).addListener(written -> channel.close());
    }

    /** The connection failed under the session. */
    void connectionLost(String message) {
        if (beginClosing(TunnelClosed.connectionLost(message), connectFailed(message))) {
            log.debug("connection lost: session={}: {}", describeSession(), message);
        }
        channel.close();
    }

    /** The user ends the session on purpose: no {@code ERROR}, a close frame with status {@code 1000} (§8.6). */
    void closeByUser() {
        if (!beginClosing(
                TunnelClosed.userClosed(),
                new TunnelStartException(
                        Kind.CONNECT_FAILED, "The client was closed before the session was set up."))) {
            return;
        }
        if (!upgraded || !channel.isActive()) {
            channel.close();
            return;
        }
        streams.cancelAll(true);
        sendCloseFrame(WebSocketCloseStatus.NORMAL_CLOSURE.code(), "");
    }

    /** Teardown, however the session ended: every stream is gone and every future completes before this returns. */
    void tearDown() {
        if (tornDown) {
            return;
        }
        tornDown = true;
        beginClosing(
                TunnelClosed.connectionLost("The connection to the server was lost."),
                connectFailed("The connection to the server closed before the session was set up."));
        cancel(handshakeDeadline);
        cancel(forceClose);
        if (streams != null) {
            try {
                streams.cancelAll(false);
            } catch (RuntimeException e) {
                log.error("session {}: could not reset its streams", describeSession(), e);
            }
        }
        if (closeReason != null) {
            log.debug("session closed: session={} reason={}", describeSession(), closeReason.reason());
            reportEnd(closeReason);
        }
    }

    /** Whether reading the connection is paused, which flow control never does. */
    boolean isReadPaused() {
        return channel != null && !channel.config().isAutoRead();
    }

    /** Calls the listener, which must not be able to break the session. */
    void tell(Consumer<TunnelEvents> event) {
        try {
            event.accept(settings.events());
        } catch (RuntimeException e) {
            log.warn("A TunnelEvents listener failed", e);
        }
    }

    private void onHandshakeDeadline() {
        if (session.isEstablished() || session.isClosing()) {
            return;
        }
        String message = "The server didn't set up the session within "
                + settings.handshakeTimeout().toMillis() + " ms.";
        startFailed(new TunnelStartException(Kind.TIMEOUT, message));
    }

    /**
     * Moves the session to closing and records why: {@code ifEstablished} for an established session, which
     * {@link #closed()} reports at teardown, or {@code ifNotEstablished}, which fails {@code start()} now. False if the
     * session was already closing, and nothing changes.
     */
    private boolean beginClosing(TunnelClosed ifEstablished, TunnelStartException ifNotEstablished) {
        if (session.isClosing()) {
            return false;
        }
        boolean established = session.isEstablished();
        session.onClosing();
        cancel(handshakeDeadline);
        if (established) {
            closeReason = ifEstablished;
        } else {
            started.completeExceptionally(ifNotEstablished);
        }
        return true;
    }

    private void sendCloseFrame(int status, String reason) {
        closeFrameSent = true;
        scheduleForceClose();
        channel.writeAndFlush(new CloseWebSocketFrame(status, reason)).addListener(written -> {
            if (!written.isSuccess()) {
                channel.close();
            }
        });
    }

    private void scheduleForceClose() {
        if (forceClose == null) {
            forceClose = channel.eventLoop()
                    .schedule(
                            () -> {
                                log.debug(
                                        "session {}: the server didn't complete the close in time", describeSession());
                                channel.close();
                            },
                            CLOSE_TIMEOUT.toNanos(),
                            TimeUnit.NANOSECONDS);
        }
    }

    private void reportEnd(TunnelClosed reason) {
        if (endReported.compareAndSet(false, true)) {
            tell(events -> events.onClosed(reason));
            closed.complete(reason);
        }
    }

    private String describeSession() {
        return session.sessionId().map(Object::toString).orElse("-");
    }

    private static TunnelStartException connectFailed(String message) {
        return new TunnelStartException(Kind.CONNECT_FAILED, message);
    }

    private static ProtocolException internalError() {
        return ProtocolException.session(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH ? message : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }
}
