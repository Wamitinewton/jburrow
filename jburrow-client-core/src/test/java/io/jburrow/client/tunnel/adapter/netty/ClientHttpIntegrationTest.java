package io.jburrow.client.tunnel.adapter.netty;

import static io.jburrow.client.tunnel.adapter.netty.HttpTunnelFixture.TIMEOUT;
import static io.jburrow.client.tunnel.adapter.netty.HttpTunnelFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.adapter.netty.HttpTunnelFixture.Response;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.wire.ErrorCode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The real client running an HTTP tunnel, against {@link ScriptedPeer} playing the server and the JDK's HTTP server as
 * the local service. The peer polices every HTTP stream rule and the windows it announces, so a response the client
 * frames wrongly, or sends past its credit, fails the test where it happens.
 *
 * @see io.jburrow.protocol.testsupport.ScriptedPeer
 */
class ClientHttpIntegrationTest {

    private static final long HUNDRED_MIB = 100L * 1024 * 1024;

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
    void aGetIsAnsweredWithItsHeadAndBodyByLength() throws Exception {
        start();

        tunnel.open(1, request("GET", "/hello?name=x", BodyFraming.EMPTY, "accept", "*/*"));
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.head().body()).isEqualTo(BodyFraming.LENGTH);
        assertThat(response.head().headers().single("content-length")).contains("5");
        assertThat(response.head().headers().single("content-type")).contains("text/plain");
        assertThat(response.body()).isEqualTo("hello");
        assertThat(response.closed()).isTrue();
        assertThat(tunnel.events().await("exchange 1", TIMEOUT))
                .isEqualTo("exchange 1 GET /hello?name=x status=200 in=0 out=5 reset=-");
        assertThat(service.requestHosts()).containsExactly("quiet-otter-4f2a.localhost");
    }

    @Test
    void aChunkedResponseArrivesWithoutItsFraming() throws Exception {
        start();

        tunnel.open(1, request("GET", "/chunked", BodyFraming.EMPTY));
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.head().body()).isEqualTo(BodyFraming.CHUNKED);
        assertThat(response.head().headers().all("transfer-encoding")).isEmpty();
        assertThat(response.body()).isEqualTo("hello, chunked");
    }

    @Test
    void aPostWithABodyOfKnownLengthIsEchoed() throws Exception {
        start();
        byte[] body = "a body of known length".getBytes(StandardCharsets.US_ASCII);

        tunnel.open(
                1,
                request(
                        "POST",
                        "/echo",
                        BodyFraming.LENGTH,
                        "content-type",
                        "text/plain",
                        "content-length",
                        String.valueOf(body.length)));
        tunnel.sendBody(1, body);
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("a body of known length");
    }

    @Test
    void aPostWithAChunkedBodyIsEchoedChunked() throws Exception {
        start();

        tunnel.open(1, request("POST", "/echo-chunked", BodyFraming.CHUNKED));
        tunnel.sendBody(1, "first ".getBytes(StandardCharsets.US_ASCII));
        tunnel.sendBody(1, "second".getBytes(StandardCharsets.US_ASCII));
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.head().body()).isEqualTo(BodyFraming.CHUNKED);
        assertThat(response.body()).isEqualTo("first second");
    }

    @Test
    void aHeadRequestGetsAnEmptyResponse() throws Exception {
        start();

        tunnel.open(1, request("HEAD", "/hello", BodyFraming.EMPTY));
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.head().body()).isEqualTo(BodyFraming.EMPTY);
        assertThat(response.bodyBytes()).isZero();
    }

    @Test
    void noContentAndNotModifiedAreEmpty() throws Exception {
        start();

        tunnel.open(1, request("GET", "/no-content", BodyFraming.EMPTY));
        tunnel.endRequest(1);
        tunnel.open(3, request("GET", "/not-modified", BodyFraming.EMPTY, "if-none-match", "\"1\""));
        tunnel.endRequest(3);

        assertThat(tunnel.response(1).status()).isEqualTo(204);
        assertThat(tunnel.response(1).head().body()).isEqualTo(BodyFraming.EMPTY);
        assertThat(tunnel.response(3).status()).isEqualTo(304);
        assertThat(tunnel.response(3).head().body()).isEqualTo(BodyFraming.EMPTY);
    }

    @Test
    void manyExchangesRunAtOnceEachOnItsOwnConnection() throws Exception {
        start();

        for (int streamId = 1; streamId <= 40; streamId += 2) {
            tunnel.open(streamId, request("GET", "/hello", BodyFraming.EMPTY));
            tunnel.endRequest(streamId);
        }

        for (int streamId = 1; streamId <= 40; streamId += 2) {
            assertThat(tunnel.response(streamId).body()).isEqualTo("hello");
        }
        assertThat(service.requestHosts()).hasSize(20);
    }

    @Test
    void aHundredMebibyteDownloadMatchesItsChecksum() throws Exception {
        start();

        tunnel.open(1, request("GET", "/download?bytes=" + HUNDRED_MIB + "&seed=7", BodyFraming.EMPTY));
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.closed()).isTrue();
        assertThat(response.bodyBytes()).isEqualTo(HUNDRED_MIB);
        assertThat(response.sha256Hex()).isEqualTo(LocalHttpService.randomSha256(HUNDRED_MIB, 7));
        assertThat(response.frames()).allSatisfy(length -> assertThat(length).isLessThanOrEqualTo(65_536));
    }

    @Test
    void aHundredMebibyteUploadMatchesItsChecksum() throws Exception {
        start();

        tunnel.open(1, request("POST", "/upload", BodyFraming.LENGTH, "content-length", String.valueOf(HUNDRED_MIB)));
        tunnel.sendRandomBody(1, HUNDRED_MIB, 11);
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.body()).isEqualTo(HUNDRED_MIB + " " + LocalHttpService.randomSha256(HUNDRED_MIB, 11));
    }

    @Test
    void aHundredMebibyteChunkedUploadMatchesItsChecksum() throws Exception {
        start();

        tunnel.open(1, request("POST", "/upload", BodyFraming.CHUNKED));
        tunnel.sendRandomBody(1, HUNDRED_MIB, 13);
        tunnel.endRequest(1);

        assertThat(tunnel.response(1).body())
                .isEqualTo(HUNDRED_MIB + " " + LocalHttpService.randomSha256(HUNDRED_MIB, 13));
    }

    @Test
    void aResponseBeforeTheRequestsEndCancelsTheRestOfTheBody() throws Exception {
        start();

        tunnel.open(1, request("POST", "/reject", BodyFraming.CHUNKED));
        tunnel.sendBody(1, new byte[1024]);
        Response response = tunnel.response(1);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.closed()).isTrue();
        assertThat(tunnel.resetAfterResponse(1)).isEqualTo(ErrorCode.CANCELLED);
        assertThat(tunnel.events().await("exchange 1", TIMEOUT)).endsWith("status=413 in=1024 out=0 reset=-");
        assertThat(tunnel.events().await("stream-closed 1", TIMEOUT)).endsWith("reset=CANCELLED");
    }

    @Test
    void theHostIsSentAsTheVisitorAskedForIt() throws Exception {
        start();

        tunnel.open(
                1,
                new HttpRequestHead("GET", "/hello", HeaderFields.of("host", "myapp.example.dev"), BodyFraming.EMPTY));
        tunnel.endRequest(1);
        tunnel.response(1);

        assertThat(service.requestHosts()).containsExactly("myapp.example.dev");
    }

    @Test
    void aLocalServiceThatIsntRunningIsUnreachable() throws Exception {
        tunnel = HttpTunnelFixture.start(LocalTarget.of(loopback(), freePort()));

        tunnel.open(1, request("GET", "/hello", BodyFraming.EMPTY));
        tunnel.endRequest(1);
        Response response = tunnel.response(1);

        assertThat(response.heads()).isEmpty();
        assertThat(response.reset()).contains(ErrorCode.LOCAL_SERVICE_UNREACHABLE);
        assertThat(tunnel.events().await("exchange 1", TIMEOUT))
                .endsWith("status=- in=0 out=0 reset=LOCAL_SERVICE_UNREACHABLE");
    }

    @Test
    void theSessionOutlivesEveryExchange() throws Exception {
        start();
        tunnel.open(1, request("GET", "/hello", BodyFraming.EMPTY));
        tunnel.endRequest(1);
        tunnel.response(1);

        tunnel.open(3, request("GET", "/hello", BodyFraming.EMPTY));
        tunnel.endRequest(3);

        assertThat(tunnel.response(3).body()).isEqualTo("hello");
        assertThat(tunnel.handle().closed().toCompletableFuture()).isNotDone();
    }

    private void start() throws Exception {
        service = new LocalHttpService();
        tunnel = HttpTunnelFixture.start(service.target());
    }

    private static String loopback() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
