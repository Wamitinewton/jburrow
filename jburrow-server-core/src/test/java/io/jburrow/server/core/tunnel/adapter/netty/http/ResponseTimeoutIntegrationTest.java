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
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §An exchange, step 7: the response timer, which runs from the end
 * of the request body ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The response timeout).
 */
class ResponseTimeoutIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(1);
    /** Three times the timeout, so a timer counted from {@code OPEN} would fire during it. */
    private static final Duration UPLOAD = RESPONSE_TIMEOUT.multipliedBy(3);

    private static final int UPLOAD_PIECES = 6;
    private static final int PIECE = 4 * 1024;

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

        long started = System.nanoTime();
        visitor.request("GET", "/slow");
        int id = open();

        Frame.Close reset = peer.expect(Frame.Close.class, TIMEOUT);
        assertThat(reset.streamId()).isEqualTo(id);
        assertThat(reset.reset()).contains(ErrorCode.CANCELLED);
        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(504);
        assertThat(response.header("jburrow-error")).isEqualTo("RESPONSE_TIMEOUT");
        assertThat(since(started)).isGreaterThanOrEqualTo(RESPONSE_TIMEOUT);
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

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void anUploadThatOutlastsTheTimeoutIsAnsweredWhenItEnds(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        long started = System.nanoTime();
        slowUpload(visitor);
        int id = receiveUpload();
        assertThat(since(started)).isGreaterThan(RESPONSE_TIMEOUT.multipliedBy(2));

        peer.send(new Frame.Headers(
                id, new HttpResponseHead(200, HeaderFields.of("content-length", "2"), BodyFraming.LENGTH)));
        peer.sendData(id, Unpooled.copiedBuffer("ok", StandardCharsets.US_ASCII), TIMEOUT);
        peer.send(Frame.Close.half(id));

        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).isEqualTo("ok");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void noResponseAfterAnUploadAnswers504OneTimeoutAfterTheUploadEnds(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        slowUpload(visitor);
        long uploadEnded = System.nanoTime();
        int id = receiveUpload();

        Frame.Close reset = peer.expect(Frame.Close.class, TIMEOUT);
        assertThat(reset.streamId()).isEqualTo(id);
        assertThat(reset.reset()).contains(ErrorCode.CANCELLED);
        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(504);
        assertThat(response.header("jburrow-error")).isEqualTo("RESPONSE_TIMEOUT");
        assertThat(since(uploadEnded))
                .isGreaterThanOrEqualTo(RESPONSE_TIMEOUT)
                .isLessThan(RESPONSE_TIMEOUT.plus(UPLOAD));
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

    /** A {@code POST} whose body arrives in pieces spread over {@link #UPLOAD}, the last at its end. */
    private static void slowUpload(Visitor visitor) throws IOException, InterruptedException {
        visitor.request("POST", "/upload", "Content-Length", Integer.toString(UPLOAD_PIECES * PIECE));
        byte[] piece = new byte[PIECE];
        Arrays.fill(piece, (byte) 'u');
        for (int i = 0; i < UPLOAD_PIECES; i++) {
            TimeUnit.MILLISECONDS.sleep(UPLOAD.toMillis() / UPLOAD_PIECES);
            visitor.send(piece);
        }
    }

    /** The upload's {@code OPEN}, its whole body as {@code DATA}, and the {@code CLOSE} that ends it. */
    private int receiveUpload() {
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        long received = 0;
        while (true) {
            Frame frame = peer.expect(Frame.class, TIMEOUT);
            assertThat(frame.streamId()).isEqualTo(id);
            if (frame instanceof Frame.Data data) {
                received += data.payload().readableBytes();
                data.release();
            } else {
                assertThat(frame)
                        .isInstanceOfSatisfying(
                                Frame.Close.class,
                                close -> assertThat(close.reset()).isEmpty());
                assertThat(received).isEqualTo((long) UPLOAD_PIECES * PIECE);
                return id;
            }
        }
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
