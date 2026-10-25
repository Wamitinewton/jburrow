package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.SessionEnd;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Side;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends sessions, the same way on every path ({@code docs/protocol.md} §8.6). A session error sends {@code ERROR}, then
 * a WebSocket close frame with the code's close status, and closes the connection when the client answers with its
 * own close frame, or after {@link #FORCE_CLOSE_TIMEOUT} if it doesn't.
 *
 * <p>Every method must be called on the session's event loop. Only the first ending counts; later calls do nothing.
 * Teardown happens when the connection closes, in {@link ControlSessionHandler}, whatever ended it.
 */
public final class SessionCloser {

    /** How long a client that was sent {@code ERROR} has to complete the close before the connection is cut. */
    public static final Duration FORCE_CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(SessionCloser.class);

    private static final String INTERNAL_ERROR_MESSAGE = "The server failed unexpectedly.";
    private static final int MAX_MESSAGE_LENGTH = 512;

    private final TunnelMetrics metrics;
    private final long forceCloseNanos;

    public SessionCloser(TunnelMetrics metrics) {
        this(metrics, FORCE_CLOSE_TIMEOUT);
    }

    SessionCloser(TunnelMetrics metrics, Duration forceCloseTimeout) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.forceCloseNanos = forceCloseTimeout.toNanos();
    }

    /**
     * Ends the session with a session error raised by this server: {@code CLOSE RST CANCELLED} for each open stream,
     * {@code ERROR}, the close frame, then the close. A stream-scope error reaching here is a bug and is sent as
     * {@code INTERNAL_ERROR}.
     */
    public void fail(SessionContext context, ProtocolException error) {
        requireLoop(context);
        ProtocolException sent = error.scope() == ErrorScope.SESSION ? error : internalError();
        if (!startClosing(context, sent.code())) {
            return;
        }
        logSessionError(context, sent);
        Channel channel = context.channel();
        if (!channel.isActive()) {
            channel.close();
            return;
        }
        context.forceClose(
                channel.eventLoop().schedule(() -> forceClose(context), forceCloseNanos, TimeUnit.NANOSECONDS));
        context.streams().ifPresent(SessionStreams::cancelAll);
        ErrorBody body = new ErrorBody(sent.code(), messageOf(sent), sent.ref().orElse(null));
        channel.writeAndFlush(new Frame.Error(body)).addListener(written -> {
            if (!written.isSuccess()) {
                channel.close();
                return;
            }
            int closeCode = sent.code().closeCode().orElseThrow();
            channel.writeAndFlush(new CloseWebSocketFrame(closeCode, sent.code().code()))
                    .addListener(closeWritten -> {
                        if (!closeWritten.isSuccess()) {
                            channel.close();
                        }
                    });
        });
    }

    /** {@link #fail} with {@code INTERNAL_ERROR}, after logging {@code cause} with the session id. */
    public void failInternally(SessionContext context, Throwable cause) {
        if (context.session().state() != SessionState.CLOSING) {
            log.error("Internal error in session {}", context.session().id(), cause);
        }
        fail(context, internalError());
    }

    /**
     * Ends the session with a session error the connection can no longer carry, because Netty's WebSocket decoder has
     * already sent its own close frame. Closes without {@code ERROR}.
     */
    void abort(SessionContext context, ErrorCode code) {
        requireLoop(context);
        if (startClosing(context, code)) {
            log.warn(
                    "session error: session={} code={} (WebSocket violation)",
                    context.session().id(),
                    code);
        }
        context.channel().close();
    }

    /**
     * The client sent {@code ERROR}: count its code and close without an {@code ERROR} of our own. The WebSocket layer
     * sends its close frame on the way out, which completes the close the client started.
     */
    void peerFailed(SessionContext context, ErrorBody error) {
        requireLoop(context);
        Session session = context.session();
        if (session.state() == SessionState.CLOSING) {
            return;
        }
        boolean established = session.state() == SessionState.ESTABLISHED;
        session.onClosing();
        context.end(SessionEnd.CLIENT_CLOSED);
        if (error.code().scopes().contains(ErrorScope.SESSION)) {
            metrics.protocolError(error.code(), Side.REMOTE);
        }
        if (!established) {
            metrics.handshakeFailed(error.code());
        }
        log.warn(
                "client error: session={} code={} message=\"{}\"",
                session.id(),
                error.code(),
                PeerText.sanitize(error.message()));
        context.channel().close();
    }

    /**
     * The client sent a WebSocket close frame. If this server started the close, the close is complete; otherwise the
     * client is ending the session on purpose, and its status is echoed before the connection closes.
     */
    void peerClosed(SessionContext context, int status) {
        requireLoop(context);
        Session session = context.session();
        Channel channel = context.channel();
        if (session.state() == SessionState.CLOSING) {
            channel.close();
            return;
        }
        session.onClosing();
        context.end(SessionEnd.CLIENT_CLOSED);
        CloseWebSocketFrame echo = status < 0 ? new CloseWebSocketFrame() : new CloseWebSocketFrame(status, "");
        channel.writeAndFlush(echo).addListener(written -> channel.close());
    }

    /** Ends the session without a session error, such as when the connection failed. */
    void close(SessionContext context, SessionEnd end) {
        requireLoop(context);
        Session session = context.session();
        if (session.state() != SessionState.CLOSING) {
            session.onClosing();
            context.end(end);
        }
        context.channel().close();
    }

    /** Counts the error and moves the session to closing; false if it was already closing. */
    private boolean startClosing(SessionContext context, ErrorCode code) {
        Session session = context.session();
        if (session.state() == SessionState.CLOSING) {
            return false;
        }
        boolean established = session.state() == SessionState.ESTABLISHED;
        session.onClosing(code);
        metrics.protocolError(code, Side.LOCAL);
        if (!established) {
            metrics.handshakeFailed(code);
        }
        return true;
    }

    private static void forceClose(SessionContext context) {
        log.debug(
                "session {}: the client didn't complete the close in time",
                context.session().id());
        context.channel().close();
    }

    private static void logSessionError(SessionContext context, ProtocolException error) {
        if (error.code() == ErrorCode.SERVER_SHUTTING_DOWN) {
            log.debug("session {}: ending for shutdown", context.session().id());
            return;
        }
        log.warn(
                "session error: session={} code={} message=\"{}\"",
                context.session().id(),
                error.code(),
                error.getMessage());
    }

    private static String messageOf(ProtocolException error) {
        String message = error.getMessage();
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static ProtocolException internalError() {
        return ProtocolException.session(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    private static void requireLoop(SessionContext context) {
        if (!context.channel().eventLoop().inEventLoop()) {
            throw new IllegalStateException("Session " + context.session().id() + " touched off its event loop");
        }
    }
}
