package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * The forwarding fields the local service sees ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Request headers the
 * edge sets): set by the edge from what a trusted proxy passed in, never from what a visitor sent itself. Loopback's
 * {@code 127.0.0.1} is the trusted ingress here; a connection from {@code 127.0.0.2} is a visitor with no proxy in
 * front of it.
 */
class ForwardedHeadersIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        private static final String SPOOFED = "198.51.100.66";

        private LocalHttpService service;
        private Tunnel tunnel;

        @BeforeEach
        void openTheTunnel() throws Exception {
            service = closeAfterTest(LocalHttpService.start());
            tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
        }

        /** The ingress appends the address it saw to whatever {@code X-Forwarded-For} the visitor sent. */
        @Test
        void aVisitorsOwnForwardingFieldsAreReplacedWithTheIngresss() throws Exception {
            HttpVisitor ingress = visitor();
            String host = tunnel.host() + ":443";

            ingress.request(
                    "GET",
                    host,
                    "/inspect",
                    "X-Forwarded-For",
                    SPOOFED + ", " + HttpVisitor.VISITOR_ADDRESS,
                    "X-Forwarded-Proto",
                    "https",
                    "X-Forwarded-Host",
                    "bank.example",
                    "X-Forwarded-Port",
                    "8443",
                    "Forwarded",
                    "for=" + SPOOFED + ";proto=https",
                    "X-Real-IP",
                    SPOOFED);
            assertThat(ingress.response().status()).isEqualTo(200);

            LocalHttpService.Received received = service.received().getLast();
            assertThat(received.fields().get("x-forwarded-for")).containsExactly(HttpVisitor.VISITOR_ADDRESS);
            assertThat(received.fields().get("x-forwarded-proto")).containsExactly("https");
            assertThat(received.fields().get("x-forwarded-host")).containsExactly(host);
            assertThat(received.fields().get("via")).containsExactly("1.1 jburrow");
            assertThat(received.fields())
                    .doesNotContainKeys("forwarded", "x-real-ip", "x-forwarded-port")
                    .doesNotContainValue(List.of(SPOOFED));
        }

        @Test
        void aVisitorWithNoTrustedProxyIsItsOwnAddressOverPlainHttp() throws Exception {
            InetAddress untrusted = InetAddress.getByName("127.0.0.2");
            HttpVisitor visitor;
            try {
                visitor = closeAfterTest(HttpVisitor.connect(untrusted, edge.boundPort(), 0, TIMEOUT));
            } catch (IOException noSecondLoopbackAddress) {
                assumeThat(noSecondLoopbackAddress)
                        .as("127.0.0.2 is bindable, as on Linux")
                        .isNull();
                return;
            }

            visitor.request("GET", tunnel.host(), "/inspect", "X-Forwarded-For", SPOOFED, "X-Forwarded-Proto", "https");
            assertThat(visitor.response().status()).isEqualTo(200);

            LocalHttpService.Received received = service.received().getLast();
            assertThat(received.fields().get("x-forwarded-for")).containsExactly("127.0.0.2");
            assertThat(received.fields().get("x-forwarded-proto")).containsExactly("http");
        }
    }
}
