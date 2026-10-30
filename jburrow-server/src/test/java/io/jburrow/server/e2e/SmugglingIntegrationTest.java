package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.OutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.TestPropertySource;

/**
 * Request smuggling between the ingress and a local service ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md}
 * §Security and threat model): a request whose framing two parsers could read differently is refused at the edge and
 * never reaches the local service, and a request hidden in a body stays body, since neither side forwards framing.
 */
class SmugglingIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        private static final String SMUGGLED = "GET /smuggled HTTP/1.1\r\nHost: localhost\r\n\r\n";

        private LocalHttpService service;
        private Tunnel tunnel;

        @BeforeEach
        void openTheTunnel() throws Exception {
            service = closeAfterTest(LocalHttpService.start());
            tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
        }

        static Stream<Arguments> ambiguousRequests() {
            return Stream.of(
                    Arguments.of(
                            "content-length with transfer-encoding",
                            "POST /echo HTTP/1.1\r\nHost: %s\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n"
                                    + "0\r\n\r\n"),
                    Arguments.of(
                            "two content-lengths",
                            "POST /echo HTTP/1.1\r\nHost: %s\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!"),
                    Arguments.of(
                            "a transfer coding before chunked",
                            "POST /echo HTTP/1.1\r\nHost: %s\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n"),
                    Arguments.of("a bare LF", "GET /hello HTTP/1.1\nHost: %s\n\n"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ambiguousRequests")
        void anAmbiguouslyFramedRequestIsRefusedAndNeverReachesTheLocalService(String what, String request)
                throws Exception {
            HttpVisitor visitor = ingressConnection();

            visitor.send(request.formatted(tunnel.host()));
            HttpVisitor.Response response = visitor.response();

            assertThat(response.status()).as(what).isEqualTo(400);
            assertThat(response.header("jburrow-error")).contains("BAD_REQUEST");
            assertThat(service.received())
                    .as("requests the local service received")
                    .isEmpty();
        }

        /**
         * RFC 9112 §5.2 lets a server replace an obs-fold with a space instead of refusing it, which Netty's decoder
         * does. The head then crosses the tunnel as JSON and is written again on the far side, so a fold can't carry a
         * field of its own to the local service.
         */
        @Test
        void anObsFoldBecomesASpaceAndCantAddAField() throws Exception {
            HttpVisitor visitor = ingressConnection();

            visitor.send("POST /inspect HTTP/1.1\r\nHost: " + tunnel.host() + "\r\nContent-Length: 0\r\n"
                    + "X-Folded: first\r\n Transfer-Encoding: chunked\r\n\r\n");
            HttpVisitor.Response response = visitor.response();

            assertThat(response.status()).isEqualTo(200);
            LocalHttpService.Received received = service.received().getLast();
            assertThat(received.fields().get("x-folded")).containsExactly("first Transfer-Encoding: chunked");
            assertThat(received.fields()).doesNotContainKey("transfer-encoding");
        }

        @Test
        void aRequestInsideAChunkedBodyArrivesAsBodyBytes() throws Exception {
            HttpVisitor visitor = ingressConnection();

            try (OutputStream body = visitor.requestBody("POST", tunnel.host(), "/inspect", -1)) {
                body.write(SMUGGLED.getBytes(US_ASCII));
            }
            HttpVisitor.Response response = visitor.response();
            visitor.request("GET", tunnel.host(), "/hello");
            HttpVisitor.Response next = visitor.response();

            assertThat(response.status()).isEqualTo(200);
            assertThat(next.text()).isEqualTo("hello\n");
            assertThat(service.received())
                    .extracting(LocalHttpService.Received::target)
                    .containsExactly("/inspect", "/hello");
            assertThat(service.received().getFirst().body()).asString(US_ASCII).isEqualTo(SMUGGLED);
        }
    }
}
