package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.wire.ErrorCode;
import io.netty.buffer.Unpooled;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class UpgradeFailuresTest {

    private static final ControlJson JSON = ControlJson.create();

    private final ControlConnection connection = new ControlConnection(new ClientSettings(
            URI.create("ws://localhost:7080/v1/connect"),
            "jb_" + "t".repeat(43),
            List.of(TunnelSpec.tcp("default", LocalTarget.of("localhost", 22))),
            new TunnelEvents() {},
            new ClientInfo("jburrow-test", "1.0.0", null, null),
            Optional.empty(),
            ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT));
    private final EmbeddedChannel channel = new EmbeddedChannel(new UpgradeFailures(connection, JSON));

    @Test
    void anAcceptedUpgradePassesOnAndTheHandlerStepsAside() {
        FullHttpResponse accepted = response(HttpResponseStatus.SWITCHING_PROTOCOLS, "");

        channel.writeInbound(accepted);

        FullHttpResponse passedOn = channel.readInbound();
        assertThat(passedOn).isSameAs(accepted);
        passedOn.release();
        assertThat(channel.pipeline().get(UpgradeFailures.class)).isNull();
        assertThat(channel.finish()).isFalse();
    }

    @Test
    void anAcceptedUpgradeWithAnExtensionFailsTheStart() {
        FullHttpResponse accepted = response(HttpResponseStatus.SWITCHING_PROTOCOLS, "");
        accepted.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");

        channel.writeInbound(accepted);

        assertThat(accepted.refCnt()).isZero();
        assertThat((Object) channel.readInbound()).isNull();
        TunnelStartException failure = startFailure();
        assertThat(failure.kind()).isEqualTo(Kind.LOCAL_ERROR);
        assertThat(failure.code()).contains(ErrorCode.PROTOCOL_ERROR);
        channel.finishAndReleaseAll();
    }

    @Test
    void aRefusalIsReadFromItsBody() {
        String body = new String(
                JSON.writeRefusal(UpgradeRefusal.unsupportedVersion(
                        "Upgrade the jburrow CLI.", List.of("jburrow.v1-draft.2"), Instant.now(), "/v1/connect")),
                StandardCharsets.UTF_8);
        FullHttpResponse refused = response(HttpResponseStatus.BAD_REQUEST, body);

        channel.writeInbound(refused);

        assertThat(refused.refCnt()).isZero();
        TunnelStartException failure = startFailure();
        assertThat(failure.kind()).isEqualTo(Kind.UPGRADE_REFUSED);
        assertThat(failure.code()).contains(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(failure.supportedVersions()).containsExactly("jburrow.v1-draft.2");
        assertThat(failure).hasMessage("Upgrade the jburrow CLI.");
        channel.finishAndReleaseAll();
    }

    @Test
    void aRefusalWithAnUnknownCodeHasNoCode() {
        String body = "{\"success\":false,\"code\":\"SOMETHING_NEW\",\"message\":\"x\","
                + "\"timestamp\":\"2026-10-19T10:15:30Z\",\"path\":\"/v1/connect\"}";

        TunnelStartException failure =
                UpgradeFailures.refusal(HttpResponseStatus.BAD_GATEWAY, body.getBytes(StandardCharsets.UTF_8), JSON);

        assertThat(failure.kind()).isEqualTo(Kind.UPGRADE_REFUSED);
        assertThat(failure.code()).isEmpty();
        assertThat(failure).hasMessageContaining("HTTP 502 Bad Gateway");
    }

    @Test
    void failuresBeforeTheUpgradeMapToTheirKind() {
        assertThat(UpgradeFailures.fromCause(
                                new DecoderException(new SSLHandshakeException("PKIX path building failed")))
                        .kind())
                .isEqualTo(Kind.TLS_FAILED);
        assertThat(UpgradeFailures.fromCause(new ConnectTimeoutException("timed out"))
                        .kind())
                .isEqualTo(Kind.CONNECT_FAILED);
        assertThat(UpgradeFailures.fromCause(new ConnectException("Connection refused"))
                        .kind())
                .isEqualTo(Kind.CONNECT_FAILED);
        assertThat(UpgradeFailures.fromCause(new UnknownHostException("nowhere.invalid")))
                .hasMessageContaining("doesn't resolve");
        TunnelStartException invalid =
                UpgradeFailures.fromCause(new WebSocketClientHandshakeException("Invalid subprotocol"));
        assertThat(invalid.kind()).isEqualTo(Kind.LOCAL_ERROR);
        assertThat(invalid.code()).contains(ErrorCode.PROTOCOL_ERROR);
        assertThat(UpgradeFailures.fromCause(new DecoderException("bad HTTP")).kind())
                .isEqualTo(Kind.UPGRADE_REFUSED);
    }

    private TunnelStartException startFailure() {
        try {
            connection.awaitStart();
        } catch (TunnelStartException e) {
            return e;
        }
        throw new AssertionError("start() succeeded");
    }

    private static FullHttpResponse response(HttpResponseStatus status, String body) {
        return new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
    }
}
