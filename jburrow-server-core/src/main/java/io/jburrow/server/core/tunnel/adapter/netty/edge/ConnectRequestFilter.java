package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets through only a valid connect request, checked in the order of {@code docs/protocol.md} §3.3, and refuses
 * anything else with the first check that fails.
 *
 * <p>A request that passes holds a {@link HandshakeTicket}, stored on the channel. The ticket is released when the
 * channel closes and nowhere else, so it is released exactly once however the connection ends.
 */
@Sharable
final class ConnectRequestFilter extends ChannelInboundHandlerAdapter {

    static final AttributeKey<HandshakeTicket> TICKET = AttributeKey.valueOf(ConnectRequestFilter.class, "ticket");

    static final String CONNECT_PATH = "/v1/connect";

    private static final Logger log = LoggerFactory.getLogger(ConnectRequestFilter.class);

    private static final String WEBSOCKET_VERSION = "13";
    private static final int WEBSOCKET_KEY_BYTES = 16;

    private final String connectHost;
    private final SessionAdmission admission;
    private final BooleanSupplier shuttingDown;
    private final TunnelMetrics metrics;
    private final UpgradeErrorResponses responses;

    ConnectRequestFilter(
            String connectHost,
            SessionAdmission admission,
            BooleanSupplier shuttingDown,
            TunnelMetrics metrics,
            UpgradeErrorResponses responses) {
        this.connectHost = Objects.requireNonNull(connectHost, "connectHost");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.shuttingDown = Objects.requireNonNull(shuttingDown, "shuttingDown");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    /** The ticket a connect request took, or null if none passed on this channel. */
    static HandshakeTicket ticket(Channel channel) {
        return channel.attr(TICKET).get();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        boolean passed = false;
        try {
            passed = check(ctx, request);
        } finally {
            if (passed) {
                ctx.fireChannelRead(request);
            } else {
                ReferenceCountUtil.release(request);
            }
        }
    }

    /** Whether the request passes; if it doesn't, the refusal has been sent. */
    private boolean check(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = pathOf(request.uri());
        HttpHeaders headers = request.headers();
        // A request Netty couldn't decode has no method, path or headers worth checking.
        if (request.decoderResult().isFailure()) {
            return refuse(ctx, ErrorCode.PROTOCOL_ERROR, "The request is not valid HTTP/1.1.", path);
        }
        if (!HttpMethod.GET.equals(request.method()) || !CONNECT_PATH.equals(path)) {
            return refuse(ctx, ErrorCode.NOT_FOUND, "Nothing is served here.", path);
        }
        if (!isConnectHost(headers.getAll(HttpHeaderNames.HOST))) {
            return refuse(ctx, ErrorCode.NOT_FOUND, "Nothing is served here.", path);
        }
        if (headers.contains(HttpHeaderNames.ORIGIN)) {
            return refuse(ctx, ErrorCode.FORBIDDEN_ORIGIN, "Upgrades from a web page are not accepted.", path);
        }
        String offered = String.join(",", headers.getAll(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL));
        if (ProtocolVersion.select(ProtocolVersion.parseHeader(offered)).isEmpty()) {
            List<String> supported = ProtocolVersion.supported();
            responses.refuseVersion(
                    ctx,
                    "This server speaks " + String.join(", ", supported) + ". Upgrade the jburrow CLI.",
                    supported,
                    path);
            return refused(ctx, ErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
        }
        if (shuttingDown.getAsBoolean()) {
            return refuse(ctx, ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down. Try again shortly.", path);
        }
        Optional<HandshakeTicket> ticket = admission.tryBeginHandshake();
        if (ticket.isEmpty()) {
            return refuse(ctx, ErrorCode.SERVER_AT_CAPACITY, "The server is at capacity. Try again shortly.", path);
        }
        hold(ctx.channel(), ticket.get());
        if (!isWebSocketHandshake(headers)) {
            return refuse(
                    ctx, ErrorCode.PROTOCOL_ERROR, "The request is not a valid WebSocket opening handshake.", path);
        }
        return true;
    }

    private void hold(Channel channel, HandshakeTicket ticket) {
        channel.attr(TICKET).set(ticket);
        channel.closeFuture().addListener(closed -> admission.release(ticket));
    }

    private boolean refuse(ChannelHandlerContext ctx, ErrorCode code, String message, String path) {
        responses.refuse(ctx, code, message, path);
        return refused(ctx, code);
    }

    private boolean refused(ChannelHandlerContext ctx, ErrorCode code) {
        metrics.handshakeFailed(code);
        log.info("Upgrade refused: client {} code {}", ClientAddressResolver.clientAddress(ctx.channel()), code);
        return false;
    }

    private boolean isConnectHost(List<String> hosts) {
        return hosts.size() == 1 && AsciiString.contentEqualsIgnoreCase(withoutPort(hosts.get(0)), connectHost);
    }

    /** {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}, without the port. */
    static String withoutPort(String host) {
        String value = host.strip();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(0, end + 1);
        }
        int colon = value.indexOf(':');
        return colon >= 0 && colon == value.lastIndexOf(':') ? value.substring(0, colon) : value;
    }

    /** The path of a request target, without its query, undecoded. */
    static String pathOf(String uri) {
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    /** RFC 6455 §4.2.1: upgrade headers, version 13, and a key that is 16 bytes in base64. */
    private static boolean isWebSocketHandshake(HttpHeaders headers) {
        return hasToken(headers.getAll(HttpHeaderNames.UPGRADE), "websocket")
                && hasToken(headers.getAll(HttpHeaderNames.CONNECTION), "upgrade")
                && headers.getAll(HttpHeaderNames.SEC_WEBSOCKET_VERSION).equals(List.of(WEBSOCKET_VERSION))
                && isWebSocketKey(headers.getAll(HttpHeaderNames.SEC_WEBSOCKET_KEY));
    }

    private static boolean hasToken(List<String> values, String token) {
        for (String value : values) {
            for (String candidate : value.split(",")) {
                if (AsciiString.contentEqualsIgnoreCase(candidate.strip(), token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWebSocketKey(List<String> keys) {
        if (keys.size() != 1) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(keys.get(0).strip()).length == WEBSOCKET_KEY_BYTES;
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }
}
