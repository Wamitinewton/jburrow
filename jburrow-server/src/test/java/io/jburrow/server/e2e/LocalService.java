package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The service a tunnel forwards to on the client's side: a blocking {@link ServerSocket} on loopback, with one virtual
 * thread per connection running a {@link Handler}. A handler that throws is recorded, and {@link #close()} fails with
 * it, so a broken service can't pass a test silently.
 */
final class LocalService implements AutoCloseable {

    /** What the service does with one accepted connection. The connection is closed when it returns. */
    @FunctionalInterface
    interface Handler {
        void handle(Socket connection) throws IOException;
    }

    /** What a visitor of {@link #bulkOnRequest} sends first to be sent the bulk. */
    static final byte[] BULK = "BULK\n".getBytes(US_ASCII);

    /**
     * The tunnel listener's, so a burst of streams dialing at once fits. Java's default of 50 overflows under
     * {@code ManyStreamsIntegrationTest}'s 500, and the kernel then resets local connections or loses their first
     * segment.
     */
    private static final int BACKLOG = 1_024;

    private static final int COPY_BUFFER = 64 * 1024;
    private static final Duration POLL = Duration.ofMillis(50);

    private final ServerSocket server;
    private final Handler handler;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile boolean closing;

    private LocalService(ServerSocket server, Handler handler) {
        this.server = server;
        this.handler = handler;
        Thread.ofVirtual().name("local-service-" + server.getLocalPort()).start(this::acceptLoop);
    }

    /** Listens on an ephemeral loopback port. */
    static LocalService start(Handler handler) {
        return start(0, handler);
    }

    /** Listens on {@code port} on loopback; 0 picks an ephemeral port. */
    static LocalService start(int port, Handler handler) {
        try {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG);
            return new LocalService(server, handler);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen on port " + port, e);
        }
    }

    /** Writes back everything it reads, and half-closes when the other side does. */
    static LocalService echo() {
        return start(connection -> {
            copy(connection.getInputStream(), connection.getOutputStream());
            connection.shutdownOutput();
        });
    }

    /**
     * Reads each connection to its end and records what arrived in {@code received}, under the id the connection
     * starts with ({@link #writeId}). Every byte after the id is added to {@code progress} as it arrives.
     */
    static LocalService sink(Map<Long, TunnelTestSupport.Received> received, AtomicLong progress) {
        return start(connection -> {
            DataInputStream in = new DataInputStream(connection.getInputStream());
            long id = in.readLong();
            received.put(id, TunnelTestSupport.drain(in, progress));
        });
    }

    /**
     * Sends each connection what it asks for with {@link #request}, then half-closes, and reads the connection to its
     * end.
     */
    static LocalService source() {
        return start(connection -> {
            DataInputStream in = new DataInputStream(connection.getInputStream());
            long length = in.readLong();
            long seed = in.readLong();
            TunnelTestSupport.send(connection.getOutputStream(), length, seed);
            connection.shutdownOutput();
            TunnelTestSupport.drain(in);
        });
    }

    /** Accepts each connection and never reads from it, until the service or the tunnel closes it. */
    static LocalService stalled() {
        return start(connection -> {
            while (!connection.isClosed()) {
                pause(POLL);
            }
        });
    }

    /**
     * Echoes, except that a connection whose first bytes are {@link #BULK} is sent {@code bulkBytes} generated from
     * {@code seed} instead, then half-closed. Bulk bytes are added to {@code bulkSent} as each write completes. Every
     * echoed message must be at least as long as {@link #BULK}.
     */
    static LocalService bulkOnRequest(long bulkBytes, long seed, AtomicLong bulkSent) {
        return start(connection -> {
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();
            byte[] first = in.readNBytes(BULK.length);
            if (Arrays.equals(first, BULK)) {
                TunnelTestSupport.send(new CountingOutputStream(out, bulkSent), bulkBytes, seed);
            } else {
                out.write(first);
                copy(in, out);
            }
            connection.shutdownOutput();
            TunnelTestSupport.drain(in);
        });
    }

    /**
     * Two connections that depend on each other. The first one accepted, A, is not read at all until the second, B,
     * has delivered {@code threshold} bytes; then A is echoed to its end. B is read to its end.
     */
    static LocalService dependentPair(int threshold) {
        AtomicInteger order = new AtomicInteger();
        CountDownLatch bDelivered = new CountDownLatch(1);
        return start(connection -> {
            if (order.getAndIncrement() == 0) {
                while (!await(bDelivered)) {
                    if (connection.isClosed()) {
                        return;
                    }
                }
                copy(connection.getInputStream(), connection.getOutputStream());
                connection.shutdownOutput();
            } else {
                connection.getInputStream().readNBytes(threshold);
                bDelivered.countDown();
                TunnelTestSupport.drain(connection.getInputStream());
            }
        });
    }

    /** What a visitor of a {@link #sink} sends first. */
    static void writeId(OutputStream out, long id) throws IOException {
        new DataOutputStream(out).writeLong(id);
    }

    /** What a visitor of a {@link #source} sends to be sent {@code length} bytes generated from {@code seed}. */
    static void request(OutputStream out, long length, long seed) throws IOException {
        DataOutputStream request = new DataOutputStream(out);
        request.writeLong(length);
        request.writeLong(seed);
        request.flush();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connections accepted so far. */
    int accepted() {
        return accepted.get();
    }

    @Override
    public void close() throws IOException {
        closing = true;
        server.close();
        for (Socket connection : connections) {
            connection.close();
        }
        if (!failures.isEmpty()) {
            AssertionError failed = new AssertionError("The local service on port " + port() + " failed");
            failures.forEach(failed::addSuppressed);
            throw failed;
        }
    }

    /** Copies until {@code in} ends, flushing after every read so an interactive exchange isn't held back. */
    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long copied = 0;
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
            out.flush();
            copied += read;
        }
        return copied;
    }

    private static void pause(Duration duration) throws InterruptedIOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    /** Waits for {@code latch} for one poll; false if it is still closed. */
    private static boolean await(CountDownLatch latch) throws InterruptedIOException {
        try {
            return latch.await(POLL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    /** Adds each completed write's length to a counter. */
    private static final class CountingOutputStream extends FilterOutputStream {

        private final AtomicLong written;

        CountingOutputStream(OutputStream out, AtomicLong written) {
            super(out);
            this.written = written;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            written.addAndGet(length);
        }
    }

    private void acceptLoop() {
        while (!closing) {
            Socket connection;
            try {
                connection = server.accept();
            } catch (IOException e) {
                if (!closing) {
                    failures.add(e);
                }
                return;
            }
            accepted.incrementAndGet();
            connections.add(connection);
            Thread.ofVirtual().start(() -> serve(connection));
        }
    }

    /**
     * A reset or broken pipe is how the tunnel ends a cancelled stream, so it is a normal ending here; each test checks
     * the bytes on the visitor's side.
     */
    private void serve(Socket connection) {
        try (connection) {
            connection.setTcpNoDelay(true);
            handler.handle(connection);
        } catch (SocketException endedByTheTunnel) {
            return;
        } catch (IOException | RuntimeException e) {
            if (!closing) {
                failures.add(e);
            }
        } finally {
            connections.remove(connection);
        }
    }
}
