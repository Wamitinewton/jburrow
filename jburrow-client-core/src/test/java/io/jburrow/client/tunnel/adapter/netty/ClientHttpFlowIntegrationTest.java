package io.jburrow.client.tunnel.adapter.netty;

import static io.jburrow.client.tunnel.adapter.netty.HttpTunnelFixture.TIMEOUT;
import static io.jburrow.client.tunnel.adapter.netty.HttpTunnelFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.adapter.netty.HttpTunnelFixture.Response;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.FrameLimits;
import io.netty.buffer.Unpooled;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An HTTP tunnel under phase 2's flow control, against {@link ScriptedPeer}, which polices the windows it announces:
 * the first {@code DATA} past the credit it gave fails the test. HTTP adds a codec between the read and the frame, and
 * these tests prove it never makes a read's body larger than the credit the read was sized by.
 */
class ClientHttpFlowIntegrationTest {

    /** The smallest windows the protocol allows, so one full frame spends either. */
    private static final Windows TINY = new Windows(65_536, 65_536);

    private static final int MEBIBYTE = 1024 * 1024;
    private static final Duration QUIET = Duration.ofMillis(300);

    private LocalHttpService service;
    private HttpTunnelFixture tunnel;

    @AfterEach
    void stop() {
        if (tunnel != null) {
            tunnel.close();
        }
        if (service != null) {
            service.close();
        }
    }

    @Test
    void aResponseStopsAtTheCreditAndGoesOnAsItReturns() throws Exception {
        start(TINY);

        tunnel.open(1, request("GET", "/download?bytes=" + MEBIBYTE + "&seed=3", BodyFraming.EMPTY));
        tunnel.endRequest(1);

        assertThat(tunnel.awaitBody(1, TINY.stream())).isEqualTo(TINY.stream());
        tunnel.assertNothingFor(QUIET);

        tunnel.peer().grant(1, 16_384);
        tunnel.peer().grantSession(16_384);
        assertThat(tunnel.awaitBody(1, TINY.stream() + 16_384)).isEqualTo(TINY.stream() + 16_384);
        tunnel.assertNothingFor(QUIET);

        tunnel.peer().autoGrant();
        Response response = tunnel.response(1);
        assertThat(response.bodyBytes()).isEqualTo(MEBIBYTE);
        assertThat(response.sha256Hex()).isEqualTo(LocalHttpService.randomSha256(MEBIBYTE, 3));
    }

    @Test
    void responsesSharingATinySessionWindowNeverOverrunIt() throws Exception {
        start(TINY);
        tunnel.peer().autoGrant();

        for (int streamId = 1; streamId <= 7; streamId += 2) {
            tunnel.open(
                    streamId, request("GET", "/download?bytes=" + MEBIBYTE + "&seed=" + streamId, BodyFraming.EMPTY));
            tunnel.endRequest(streamId);
        }

        for (int streamId = 1; streamId <= 7; streamId += 2) {
            Response response = tunnel.response(streamId);
            assertThat(response.sha256Hex()).isEqualTo(LocalHttpService.randomSha256(MEBIBYTE, streamId));
            assertThat(response.frames())
                    .allSatisfy(length -> assertThat(length).isLessThanOrEqualTo(TINY.stream()));
        }
    }

    @Test
    void aLocalServiceThatStopsReadingAnUploadHoldsBackOnlyItsStream() throws Exception {
        start(ScriptedPeer.SERVER_WINDOWS);
        tunnel.peer().autoGrant();

        tunnel.open(1, request("POST", "/stall", BodyFraming.CHUNKED));
        service.awaitStalled();
        Future<Long> stalled = tunnel.threads().submit(() -> sendUntilNoCredit(1));
        long held = stalled.get(TIMEOUT.toSeconds() * 4, TimeUnit.SECONDS);
        assertThat(tunnel.peer().peerStreamCredit(1)).isLessThan(FrameLimits.MAX_DATA_PAYLOAD);

        tunnel.open(3, request("POST", "/upload", BodyFraming.LENGTH, "content-length", String.valueOf(MEBIBYTE)));
        tunnel.sendRandomBody(3, MEBIBYTE, 5);
        tunnel.endRequest(3);
        assertThat(tunnel.response(3).body()).isEqualTo(MEBIBYTE + " " + LocalHttpService.randomSha256(MEBIBYTE, 5));
        tunnel.assertNothingFor(QUIET);

        service.releaseStall();
        tunnel.endRequest(1);
        assertThat(tunnel.response(1).body()).startsWith(held + " ");
        assertThat(tunnel.peer().peerSessionCredit()).isPositive();
    }

    private void start(Windows serverWindows) throws Exception {
        service = new LocalHttpService();
        tunnel = HttpTunnelFixture.withWindows(service.target(), serverWindows);
    }

    /** Sends full frames on {@code streamId} as credit allows, until none comes for a while; returns the bytes sent. */
    private long sendUntilNoCredit(int streamId) {
        long sent = 0;
        while (true) {
            try {
                tunnel.peer()
                        .sendData(
                                streamId,
                                Unpooled.wrappedBuffer(new byte[FrameLimits.MAX_DATA_PAYLOAD]),
                                QUIET.multipliedBy(4));
            } catch (AssertionError noCredit) {
                return sent;
            }
            sent += FrameLimits.MAX_DATA_PAYLOAD;
        }
    }
}
