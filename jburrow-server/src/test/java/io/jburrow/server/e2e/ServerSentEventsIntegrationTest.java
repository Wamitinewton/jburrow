package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * A streamed response passes through as it is produced ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Testing):
 * each server-sent event reaches the visitor within 100 ms of the local service writing it, so nothing on the way
 * buffers a response until it ends or fills a buffer.
 */
class ServerSentEventsIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        private static final int EVENTS = 10;
        private static final Duration INTERVAL = Duration.ofMillis(200);
        private static final Duration DELIVERY_BOUND = Duration.ofMillis(100);

        @Test
        void eachEventReachesTheVisitorAsItIsWritten() throws Exception {
            LocalHttpService service = closeAfterTest(LocalHttpService.start());
            Tunnel tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
            HttpVisitor visitor = ingressConnection();

            visitor.request(
                    "GET",
                    tunnel.host(),
                    "/sse?count=" + EVENTS + "&interval=" + INTERVAL.toMillis(),
                    "Accept",
                    "text/event-stream");
            HttpVisitor.Head head = visitor.finalHead();
            List<String> events = new ArrayList<>();
            List<Long> arrived = new ArrayList<>();
            try (InputStream body = visitor.body(head)) {
                ByteArrayOutputStream event = new ByteArrayOutputStream();
                for (int b = body.read(); b >= 0; b = body.read()) {
                    event.write(b);
                    if (event.toString(UTF_8).endsWith("\n\n")) {
                        arrived.add(System.nanoTime());
                        events.add(event.toString(UTF_8));
                        event.reset();
                    }
                }
            }

            assertThat(head.status()).isEqualTo(200);
            assertThat(head.header("content-type")).contains("text/event-stream");
            assertThat(events).hasSize(EVENTS).startsWith("id: 0\ndata: event 0\n\n");
            List<Long> written = service.eventsWritten();
            for (int i = 0; i < EVENTS; i++) {
                assertThat(Duration.ofNanos(arrived.get(i) - written.get(i)))
                        .as("time from the local service writing event %d to the visitor reading it", i)
                        .isLessThan(DELIVERY_BOUND);
            }
        }
    }
}
