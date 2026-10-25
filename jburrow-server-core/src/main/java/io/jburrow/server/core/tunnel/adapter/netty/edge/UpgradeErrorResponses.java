package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.wire.ErrorCode;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;

/** Writes the refused-upgrade response of {@code docs/protocol.md} §3.5, then closes the connection. */
final class UpgradeErrorResponses {

    /** When a client refused for capacity or shutdown may try again. */
    static final int RETRY_AFTER_SECONDS = 5;

    private final ControlJson json;
    private final InstantSource clock;

    UpgradeErrorResponses(ControlJson json, InstantSource clock) {
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void refuse(ChannelHandlerContext ctx, ErrorCode code, String message, String path) {
        send(ctx, UpgradeRefusal.of(code, message, clock.instant(), path));
    }

    void refuseVersion(ChannelHandlerContext ctx, String message, List<String> supported, String path) {
        send(ctx, UpgradeRefusal.unsupportedVersion(message, supported, clock.instant(), path));
    }

    FullHttpResponse response(UpgradeRefusal refusal) {
        int status = refusal.code()
                .upgradeStatus()
                .orElseThrow(() -> new IllegalArgumentException(refusal.code() + " has no upgrade status"));
        byte[] body = json.writeRefusal(refusal);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(body));
        HttpHeaders headers = response.headers();
        headers.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        headers.set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        headers.set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        if (refusal.code() == ErrorCode.SERVER_AT_CAPACITY || refusal.code() == ErrorCode.SERVER_SHUTTING_DOWN) {
            headers.set(HttpHeaderNames.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response;
    }

    private void send(ChannelHandlerContext ctx, UpgradeRefusal refusal) {
        ctx.writeAndFlush(response(refusal)).addListener(ChannelFutureListener.CLOSE);
    }
}
