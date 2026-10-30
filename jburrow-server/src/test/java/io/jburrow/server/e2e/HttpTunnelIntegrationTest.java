package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.TunnelStartException;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.OutputStream;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * HTTP tunnels through the real server and the real client to a JDK HTTP server
 * ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Testing): the method and status matrix on one keep-alive
 * connection, an informational response, host-only cookies, targets that arrive as they were sent, and the page for a
 * name nobody serves, held or not.
 */
class HttpTunnelIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        private static final byte[] BODY = "{\"name\":\"otter\",\"count\":27}".getBytes(UTF_8);

        private LocalHttpService service;
        private Tunnel tunnel;

        @BeforeEach
        void openTheTunnel() throws Exception {
            service = closeAfterTest(LocalHttpService.start());
            tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
        }

        @Test
        void theMethodAndStatusMatrixOnOneKeepAliveConnection() throws Exception {
            HttpVisitor visitor = ingressConnection();
            String host = tunnel.host();

            visitor.request("GET", host, "/hello");
            assertThat(visitor.response().text()).isEqualTo("hello\n");

            visitor.request("POST", host, "/echo", BODY, "Content-Type", "application/json");
            HttpVisitor.Response echoed = visitor.response();
            assertThat(echoed.status()).isEqualTo(200);
            assertThat(echoed.header("content-length")).contains(Integer.toString(BODY.length));
            assertThat(echoed.body()).isEqualTo(BODY);

            try (OutputStream body = visitor.requestBody("POST", host, "/echo", -1)) {
                body.write(BODY, 0, 10);
                body.flush();
                body.write(BODY, 10, BODY.length - 10);
            }
            assertThat(visitor.response().body()).isEqualTo(BODY);
            assertThat(service.received().getLast().fields())
                    .as("a chunked body is framed again on the far side")
                    .containsKey("transfer-encoding")
                    .doesNotContainKey("content-length");

            visitor.request("HEAD", host, "/hello");
            HttpVisitor.Response head = visitor.response(true);
            assertThat(head.status()).isEqualTo(200);

            visitor.request("GET", host, "/status/204");
            assertThat(visitor.response().status()).isEqualTo(204);

            visitor.request("GET", host, "/status/304");
            assertThat(visitor.response().status()).isEqualTo(304);

            visitor.request("DELETE", host, "/status/202");
            assertThat(visitor.response().status()).isEqualTo(202);

            visitor.request("GET", host, "/missing");
            assertThat(visitor.response().status()).isEqualTo(404);

            visitor.request("GET", host, "/hello");
            assertThat(visitor.response().text())
                    .as("after a HEAD, a 204 and a 304, the connection still frames responses right")
                    .isEqualTo("hello\n");
            assertThat(visitor.closedByEdge()).isFalse();
        }

        @Test
        void anInformationalResponseReachesTheVisitorBeforeTheFinalOne() throws Exception {
            LocalService hints = closeAfterTest(LocalService.rawHttp("HTTP/1.1 103 Early Hints\r\n"
                    + "Link: </style.css>; rel=preload\r\n\r\n"
                    + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"));
            Tunnel hinted = openHttpTunnel(BOB_TOKEN, hints.port());
            HttpVisitor visitor = ingressConnection();

            visitor.request("GET", hinted.host(), "/");

            HttpVisitor.Head early = visitor.head();
            assertThat(early.status()).isEqualTo(103);
            assertThat(early.header("link")).contains("</style.css>; rel=preload");
            HttpVisitor.Response response = visitor.response();
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text()).isEqualTo("ok");
        }

        @Test
        void everyCookieATunnelSetsIsHostOnly() throws Exception {
            HttpVisitor visitor = ingressConnection();

            visitor.request("GET", tunnel.host(), "/cookie");
            HttpVisitor.Response response = visitor.response();

            assertThat(response.head().all("set-cookie"))
                    .containsExactly("session=local-http-service; Path=/", "theme=dark; Path=/; HttpOnly");
        }

        @Test
        void theTargetArrivesExactlyAsTheVisitorSentIt() throws Exception {
            String target = "/inspect/a%20b%2Fc;v=1/%7Euser?q=%41&x=1+2&empty=&check=%E2%9C%93&&dup=1&dup=2";
            HttpVisitor visitor = ingressConnection();

            visitor.request("GET", tunnel.host(), target);

            assertThat(visitor.response().text()).startsWith("GET " + target + "\n");
            assertThat(service.received().getLast().target()).isEqualTo(target);
        }

        @Test
        void aNameNobodyServesGetsThePage() throws Exception {
            HttpVisitor visitor = ingressConnection();

            visitor.request("GET", "nobody-serves-this.localhost", "/hello");
            HttpVisitor.Response response = visitor.response();

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.header("jburrow-error")).contains("TUNNEL_NOT_FOUND");
            assertThat(response.header("content-type")).contains("text/html; charset=utf-8");
            assertThat(response.text()).contains("nobody-serves-this.localhost");
        }

        /** The hold keeps the name for its principal; it doesn't serve it. */
        @Test
        void aHeldNameGetsThePageAndCantBeTakenByAnotherPrincipal() throws Exception {
            String name = "held-"
                    + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
            Tunnel held = openTunnel(httpClient(BOB_TOKEN, service.port()).subdomain(name));
            HttpVisitor visitor = ingressConnection();
            visitor.request("GET", held.host(), "/hello");
            assertThat(visitor.response().text()).isEqualTo("hello\n");

            double sessions = gauge("jburrow.sessions.active");
            held.client().close();
            held.awaitClosed(TIMEOUT);
            await("the server has ended the session", () -> gauge("jburrow.sessions.active") == sessions - 1);
            visitor.request("GET", held.host(), "/hello");
            HttpVisitor.Response response = visitor.response();

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.header("jburrow-error")).contains("TUNNEL_NOT_FOUND");
            assertThatThrownBy(() ->
                            openTunnel(httpClient(ALICE_TOKEN, service.port()).subdomain(name)))
                    .isInstanceOfSatisfying(
                            TunnelStartException.class,
                            refused -> assertThat(refused.code()).contains(ErrorCode.SUBDOMAIN_UNAVAILABLE));
        }
    }
}
