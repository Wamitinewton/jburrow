package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.http.Visitor.Response;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §An exchange, step 7: the response timer. */
class ResponseTimeoutIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(400);

    private HttpHarness harness;
    private ScriptedPeer peer;
    private RegisteredTunnel tunnel;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void noResponseHeadInTimeResetsTheStreamAndAnswers504(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        visitor.request("GET", "/slow");
        int id = open();

        Frame.Close reset = peer.expect(Frame.Close.class, TIMEOUT);
        assertThat(reset.streamId()).isEqualTo(id);
        assertThat(reset.reset()).contains(ErrorCode.CANCELLED);
        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(504);
        assertThat(response.header("jburrow-error")).isEqualTo("RESPONSE_TIMEOUT");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void informationalHeadsDontStopTheTimer(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        visitor.request("GET", "/hints");
        int id = open();
        for (int i = 0; i < 3; i++) {
            peer.send(new Frame.Headers(id, new HttpResponseHead(103, HeaderFields.of("link", "</a.css>"), null)));
            TimeUnit.MILLISECONDS.sleep(RESPONSE_TIMEOUT.toMillis() / 4);
        }

        assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).contains(ErrorCode.CANCELLED);
        Response response = visitor.response();
        while (response.status() == 103) {
            response = visitor.response();
        }
        assertThat(response.status()).isEqualTo(504);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aFinalHeadInTimeStopsTheTimerForAResponseThatTakesLonger(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        visitor.request("GET", "/stream");
        int id = open();
        peer.send(new Frame.Headers(id, new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED)));
        TimeUnit.MILLISECONDS.sleep(RESPONSE_TIMEOUT.toMillis() * 2);
        peer.send(Frame.Close.half(id));

        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    private void start(EdgeTransport transport) {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport).responseTimeout(RESPONSE_TIMEOUT));
        peer = harness.connect();
        peer.ignoreWindowUpdates();
        tunnel = harness.establish(peer, "slow-service");
    }

    private int open() {
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).isEmpty();
        return id;
    }
}
