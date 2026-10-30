package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Paused readers, on the server: responses whose visitors stop
 * reading are parked, so their bytes stop holding the session window shut and other requests on the tunnel go on.
 */
class PausedReadersIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final Duration PARK_AFTER = Duration.ofMillis(200);
    private static final int STALLED = 10;
    private static final int STREAM_WINDOW = HttpHarness.SERVER_WINDOWS.stream();
    private static final int FRAME = 64 * 1024;
    private static final int SOCKET_BUFFER = 16 * 1024;
    private static final int VISITOR_RECEIVE_BUFFER = 4 * 1024;

    private HttpHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void parkingLetsAnotherRequestCompleteBesideTenStalledResponses(EdgeTransport transport) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport)
                .parking(PARK_AFTER, 16L * 1024 * 1024, 4L * 1024 * 1024, SOCKET_BUFFER));
        ScriptedPeer peer = connect();
        RegisteredTunnel tunnel = harness.establish(peer, "videos");
        List<Visitor> paused = new ArrayList<>();

        for (int i = 0; i < STALLED; i++) {
            Visitor visitor = harness.slowVisitor(tunnel.publicHost(), VISITOR_RECEIVE_BUFFER);
            paused.add(visitor);
            visitor.request("GET", "/video/" + i);
            int id = open(peer);
            peer.send(new Frame.Headers(id, new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED)));
            for (int sent = 0; sent < STREAM_WINDOW; sent += FRAME) {
                peer.sendData(id, Unpooled.wrappedBuffer(new byte[FRAME]), TIMEOUT);
            }
        }

        Visitor probe = harness.visitor(tunnel.publicHost());
        probe.request("GET", "/health");
        respond(peer, open(peer), "ok");

        assertThat(probe.response().text()).isEqualTo("ok");
        assertThat(harness.metrics.events()).contains("streamParked");
        assertThat(harness.metrics.parkedBytes().getAsLong()).isPositive();

        for (Visitor visitor : paused) {
            visitor.close();
        }
        awaitTrue(() -> harness.metrics.parkedBytes().getAsLong() == 0);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void withParkingOffTwoStalledResponsesHoldTheSessionWindowShut(EdgeTransport transport) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport).parking(PARK_AFTER, 0, 0, SOCKET_BUFFER));
        ScriptedPeer peer = connect();
        RegisteredTunnel tunnel = harness.establish(peer, "videos");
        int sessionWindow = HttpHarness.SERVER_WINDOWS.session();

        for (int i = 0; i < sessionWindow / STREAM_WINDOW; i++) {
            Visitor visitor = harness.slowVisitor(tunnel.publicHost(), VISITOR_RECEIVE_BUFFER);
            visitor.request("GET", "/video/" + i);
            int id = open(peer);
            peer.send(new Frame.Headers(id, new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED)));
            for (int sent = 0; sent < STREAM_WINDOW; sent += FRAME) {
                peer.sendData(id, Unpooled.wrappedBuffer(new byte[FRAME]), TIMEOUT);
            }
        }

        Visitor probe = harness.visitor(tunnel.publicHost());
        probe.request("GET", "/health");
        int id = open(peer);
        peer.send(new Frame.Headers(id, head(2)));

        assertThat(probe.response(true).status()).isEqualTo(200);
        assertThatThrownBy(() -> peer.sendData(id, ascii("ok"), PARK_AFTER.multipliedBy(5)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("session credit 0");
        assertThat(harness.metrics.events()).doesNotContain("streamParked");
    }

    private ScriptedPeer connect() {
        ScriptedPeer peer = harness.connect();
        peer.ignoreWindowUpdates();
        return peer;
    }

    /** The next exchange's stream, once its {@code OPEN} and the {@code CLOSE} of its empty body have arrived. */
    private static int open(ScriptedPeer peer) {
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).isEmpty();
        return id;
    }

    private static void respond(ScriptedPeer peer, int id, String body) {
        peer.send(new Frame.Headers(id, head(body.length())));
        peer.sendData(id, ascii(body), TIMEOUT);
        peer.send(Frame.Close.half(id));
    }

    private static HttpResponseHead head(int length) {
        return new HttpResponseHead(
                200, HeaderFields.of("content-length", Integer.toString(length)), BodyFraming.LENGTH);
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }
}
