package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.SSLException;

/**
 * Turns whatever stops a session before its WebSocket upgrade completes into a {@link TunnelStartException}.
 *
 * <p>As a handler, it sits between the HTTP aggregator and Netty's WebSocket handshaker and reads the server's answer
 * to the upgrade first. A refusal ({@code docs/protocol.md} §3.5) carries its code in a JSON body, and Netty's
 * {@link WebSocketClientHandshakeException} keeps the status and headers of a refusal but drops the body, so the body is
 * read here and the handshaker never sees the refusal. A {@code 101} is checked for an extension, which a client must
 * fail on (§3.4) and Netty doesn't, and passed on; the handler then removes itself.
 */
final class UpgradeFailures extends ChannelInboundHandlerAdapter {

    private final ControlConnection connection;
    private final ControlJson json;

    UpgradeFailures(ControlConnection connection, ControlJson json) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpResponse response)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (response.status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            if (response.headers().contains(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS)) {
                response.release();
                connection.startFailed(protocolViolation(
                        "The server accepted a WebSocket extension, which this" + " protocol never negotiates."));
                return;
            }
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(response);
            return;
        }
        TunnelStartException refused;
        try {
            refused = refusal(response.status(), ByteBufUtil.getBytes(response.content()), json);
        } finally {
            response.release();
        }
        connection.startFailed(refused);
    }

    /**
     * A refused upgrade. The client decides on the body's {@code code} alone; a body it can't read a known code from is
     * a failure to connect, since it usually comes from something between the client and the server (§3.5).
     */
    static TunnelStartException refusal(HttpResponseStatus status, byte[] body, ControlJson json) {
        UpgradeRefusal refusal;
        try {
            refusal = json.readRefusal(body);
        } catch (ProtocolException unreadable) {
            return new TunnelStartException(
                    Kind.UPGRADE_REFUSED,
                    "The upgrade was refused with HTTP " + status.code() + " "
                            + PeerText.sanitize(status.reasonPhrase())
                            + " and no JBurrow error code; something between this client and the server may have"
                            + " answered.");
        }
        return new TunnelStartException(
                Kind.UPGRADE_REFUSED,
                PeerText.sanitize(refusal.message()),
                Optional.of(refusal.code()),
                refusal.supported() == null ? List.of() : refusal.supported(),
                null);
    }

    /** What a failure before the upgrade completed means for {@code start()}. */
    static TunnelStartException fromCause(Throwable cause) {
        SSLException tls = find(cause, SSLException.class);
        if (tls != null) {
            return new TunnelStartException(
                    Kind.TLS_FAILED, "TLS failed: " + tls.getMessage(), Optional.empty(), List.of(), tls);
        }
        if (cause instanceof WebSocketClientHandshakeException invalid) {
            return protocolViolation(
                    "The server's answer to the WebSocket upgrade is not valid: " + invalid.getMessage());
        }
        if (cause instanceof ConnectTimeoutException) {
            return connectFailed("Connecting to the server timed out.", cause);
        }
        if (cause instanceof UnknownHostException) {
            return connectFailed("The server's name doesn't resolve: " + cause.getMessage(), cause);
        }
        if (cause instanceof IOException) {
            return connectFailed("Couldn't connect to the server: " + cause.getMessage(), cause);
        }
        if (cause instanceof DecoderException) {
            return new TunnelStartException(
                    Kind.UPGRADE_REFUSED,
                    "The server's answer to the upgrade is not valid HTTP.",
                    Optional.empty(),
                    List.of(),
                    cause);
        }
        return connectFailed("Couldn't connect to the server: " + cause, cause);
    }

    static TunnelStartException protocolViolation(String message) {
        return new TunnelStartException(
                Kind.LOCAL_ERROR, message, Optional.of(ErrorCode.PROTOCOL_ERROR), List.of(), null);
    }

    private static TunnelStartException connectFailed(String message, Throwable cause) {
        return new TunnelStartException(Kind.CONNECT_FAILED, message, Optional.empty(), List.of(), cause);
    }

    private static <T extends Throwable> T find(Throwable cause, Class<T> type) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
