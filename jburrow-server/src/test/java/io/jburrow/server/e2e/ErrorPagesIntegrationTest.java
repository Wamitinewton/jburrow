package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * Every page an exchange can end with once a tunnel has the request ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md}
 * §Error pages), through the real client: its status, its code, and, for the pages the edge answers alone, that the
 * local service never sees the request. The response timeout is a second and the body limit 1 MB here, so the timeout
 * and the limit are quick to reach.
 */
class ErrorPagesIntegrationTest {

    private static final String SHORT_LIMITS = "jburrow.http.response-timeout=1s";
    private static final String SMALL_BODIES = "jburrow.http.max-request-body=1MB";

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=30960-30969",
                ON_EPOLL,
                LOOPBACK_IS_THE_INGRESS,
                KERNEL_SOCKET_BUFFERS,
                SHORT_LIMITS,
                SMALL_BODIES
            })
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=30970-30979",
                ON_NIO,
                LOOPBACK_IS_THE_INGRESS,
                KERNEL_SOCKET_BUFFERS,
                SHORT_LIMITS,
                SMALL_BODIES
            })
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        private static final int OVER_THE_LIMIT = 2 * 1024 * 1024;

        @Test
        void aLocalServiceThatIsntRunningIsUnreachable() throws Exception {
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, unusedPort());

            HttpVisitor.Response response = get(tunnel, "/hello");

            assertPage(response, 502, "LOCAL_SERVICE_UNREACHABLE");
            ExchangeSummary exchange = tunnel.events().awaitExchange(TIMEOUT);
            assertThat(exchange.reset()).contains(ErrorCode.LOCAL_SERVICE_UNREACHABLE);
            assertThat(exchange.status()).isEmpty();
        }

        @Test
        void aLocalServiceThatAnswersWithoutHttpIsALocalServiceError() throws Exception {
            LocalService broken = closeAfterTest(LocalService.rawHttp("SSH-2.0-OpenSSH_9.6\r\n\r\n"));
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, broken.port());

            HttpVisitor.Response response = get(tunnel, "/hello");

            assertPage(response, 502, "LOCAL_SERVICE_ERROR");
            assertThat(tunnel.events().awaitExchange(TIMEOUT).reset()).contains(ErrorCode.LOCAL_SERVICE_ERROR);
        }

        @Test
        void aLocalServiceThatDoesntAnswerInTimeIsAResponseTimeout() throws Exception {
            LocalHttpService sleepy = closeAfterTest(LocalHttpService.start());
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, sleepy.port());
            long started = System.nanoTime();

            HttpVisitor.Response response = get(tunnel, "/sleep?ms=5000");

            assertPage(response, 504, "RESPONSE_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("time to the page, with a one-second response timeout")
                    .isLessThan(Duration.ofSeconds(4));
            assertThat(tunnel.events().awaitExchange(TIMEOUT).reset()).contains(ErrorCode.CANCELLED);
        }

        @Test
        void anUpgradeIsNotSupportedAndNeverReachesTheLocalService() throws Exception {
            LocalHttpService service = closeAfterTest(LocalHttpService.start());
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
            HttpVisitor visitor = ingressConnection();

            visitor.request(
                    "GET",
                    tunnel.host(),
                    "/socket",
                    "Connection",
                    "Upgrade",
                    "Upgrade",
                    "websocket",
                    "Sec-WebSocket-Version",
                    "13",
                    "Sec-WebSocket-Key",
                    "dGhlIHNhbXBsZSBub25jZQ==");

            assertPage(visitor.response(), 501, "UPGRADE_NOT_SUPPORTED");
            assertThat(service.received()).isEmpty();
        }

        @Test
        void aBodyDeclaredOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
            LocalHttpService service = closeAfterTest(LocalHttpService.start());
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
            HttpVisitor visitor = ingressConnection();

            visitor.requestBody("POST", tunnel.host(), "/digest", OVER_THE_LIMIT);

            assertPage(visitor.response(), 413, "REQUEST_TOO_LARGE");
            assertThat(service.received()).isEmpty();
        }

        @Test
        void aChunkedBodyThatPassesTheLimitIsRefusedIfNoResponseHasStarted() throws Exception {
            LocalHttpService service = closeAfterTest(LocalHttpService.start());
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
            HttpVisitor visitor = ingressConnection();

            OutputStream body = visitor.requestBody("POST", tunnel.host(), "/digest", -1);
            async(() -> {
                try {
                    send(body, OVER_THE_LIMIT, 1);
                    body.close();
                } catch (IOException closedByTheEdge) {
                    // The edge stops reading once it has answered.
                }
                return null;
            });

            assertPage(visitor.response(), 413, "REQUEST_TOO_LARGE");
            assertThat(visitor.closedByEdge())
                    .as("the rest of the body can't be framed, so the connection ends")
                    .isTrue();
            assertThat(tunnel.events().awaitExchange(TIMEOUT).reset()).contains(ErrorCode.CANCELLED);
        }

        private HttpVisitor.Response get(Tunnel tunnel, String target) throws IOException {
            HttpVisitor visitor = ingressConnection();
            visitor.request("GET", tunnel.host(), target);
            return visitor.response();
        }

        private static void assertPage(HttpVisitor.Response response, int status, String code) {
            assertThat(response.status()).isEqualTo(status);
            assertThat(response.header("jburrow-error")).contains(code);
            assertThat(response.header("cache-control")).contains("no-store");
            assertThat(response.text()).contains(code);
        }
    }
}
