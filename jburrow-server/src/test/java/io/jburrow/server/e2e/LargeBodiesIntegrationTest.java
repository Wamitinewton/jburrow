package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * Bodies stream both ways under the flow windows ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Testing, §Memory
 * budget): 100 MiB up and down, framed by length and by chunks, arrive with the same SHA-256, while the direct memory
 * buffers hold stays within the budget and the heap doesn't grow with the bytes carried.
 *
 * <p>Both readings cover the whole JVM, so {@link #DIRECT_BOUND} allows for the client too, as
 * {@link SlowClientIntegrationTest}'s does: the server's per-session budget (≈ 0.9 MiB), the client's 1 MiB session
 * window, its control channel's buffer and a frame. Buffering a body would need 100 MiB.
 */
class LargeBodiesIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    @Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith(FlowStateDump.class)
    abstract static class Scenario extends TunnelTestSupport {

        /** {@code jburrow.http.max-request-body}'s default, exactly: the largest body the edge forwards. */
        private static final long LENGTH = 100L * 1024 * 1024;

        private static final long SEED = 42;
        private static final Duration DEADLINE = Duration.ofMinutes(2);
        private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(250);
        private static final long DIRECT_BOUND = 4 * 1024 * 1024;
        /** Everything an exchange allocates exists by its first quarter; nothing may grow with the bytes after it. */
        private static final long HEAP_GROWTH_BOUND = 16 * 1024 * 1024;

        private Tunnel tunnel;

        @BeforeEach
        void openTheTunnel() throws Exception {
            LocalHttpService service = closeAfterTest(LocalHttpService.start());
            tunnel = openHttpTunnel(ALICE_TOKEN, service.port());
        }

        @Test
        void anUploadOfKnownLength() throws Exception {
            upload(LENGTH);
        }

        @Test
        void aChunkedUpload() throws Exception {
            upload(-1);
        }

        @Test
        void aDownloadOfKnownLength() throws Exception {
            download("/bytes/" + LENGTH);
        }

        @Test
        void aChunkedDownload() throws Exception {
            download("/bytes/" + LENGTH + "?chunked=1");
        }

        /** Sends {@link #LENGTH} bytes framed by {@code length}, or by chunks if negative, to the digesting service. */
        private void upload(long length) throws Exception {
            HttpVisitor visitor = ingressConnection();
            visitor.timeout(DEADLINE);
            AtomicLong progress = new AtomicLong();
            OutputStream body = visitor.requestBody("POST", tunnel.host(), "/digest", length);

            HttpVisitor.Response response;
            try (MemoryProbe memory = MemoryProbe.sampleEvery(SAMPLE_INTERVAL)) {
                CompletableFuture<String> sent = async(() -> {
                    try (OutputStream counted = counting(body, progress)) {
                        return send(counted, LENGTH, SEED);
                    }
                });
                long heapAtQuarter = heapAfterGcOnceCarried(progress, LENGTH / 4);
                sent.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                response = visitor.response();
                assertMemoryFlat(length < 0 ? "chunked upload" : "upload", memory, heapAtQuarter);
            }

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text()).isEqualTo(LENGTH + " " + expectedDigest(LENGTH, SEED));
        }

        /** Downloads {@link #LENGTH} bytes, seeded by their length, from {@code target}. */
        private void download(String target) throws Exception {
            HttpVisitor visitor = ingressConnection();
            visitor.timeout(DEADLINE);
            AtomicLong progress = new AtomicLong();
            visitor.request("GET", tunnel.host(), target);
            HttpVisitor.Head head = visitor.finalHead();

            Received received;
            try (MemoryProbe memory = MemoryProbe.sampleEvery(SAMPLE_INTERVAL);
                    InputStream body = visitor.body(head)) {
                CompletableFuture<Received> reading = async(() -> drain(body, progress));
                long heapAtQuarter = heapAfterGcOnceCarried(progress, LENGTH / 4);
                received = reading.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                assertMemoryFlat(head.chunked() ? "chunked download" : "download", memory, heapAtQuarter);
            }

            assertThat(head.status()).isEqualTo(200);
            assertThat(head.chunked()).isEqualTo(target.endsWith("chunked=1"));
            assertThat(received).isEqualTo(new Received(LENGTH, expectedDigest(LENGTH, LENGTH)));
        }

        private static long heapAfterGcOnceCarried(AtomicLong progress, long bytes) throws InterruptedException {
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (progress.get() < bytes) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Only " + progress.get() + " of " + bytes + " bytes in " + DEADLINE);
                }
                Thread.sleep(50);
            }
            return MemoryProbe.heapAfterGc();
        }

        private void assertMemoryFlat(String transfer, MemoryProbe memory, long heapAtQuarter)
                throws InterruptedException {
            long heapAtEnd = MemoryProbe.heapAfterGc();
            long mostDirect = memory.mostDirectGrowth();
            System.out.printf(
                    "Large bodies on %s, %s: most direct bytes held %d KiB, heap after GC %d MiB at the first quarter"
                            + " and %d MiB at the end%n",
                    edge.transportName(), transfer, mostDirect / 1024, heapAtQuarter >> 20, heapAtEnd >> 20);
            assertThat(mostDirect).as("most direct bytes held, %s", transfer).isLessThanOrEqualTo(DIRECT_BOUND);
            assertThat(heapAtEnd)
                    .as("heap after GC at the end, %s", transfer)
                    .isLessThanOrEqualTo(heapAtQuarter + HEAP_GROWTH_BOUND);
        }

        private static OutputStream counting(OutputStream out, AtomicLong progress) {
            return new FilterOutputStream(out) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                    progress.addAndGet(length);
                }
            };
        }
    }
}
