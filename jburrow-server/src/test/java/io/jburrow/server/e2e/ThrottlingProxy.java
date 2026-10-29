package io.jburrow.server.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TCP proxy on loopback that forwards each direction of every connection at a fixed rate: a deliberately slow client
 * without touching client code. The client connects to {@link #port()} instead of the edge; the {@code Host} header it
 * sends still names {@code localhost}, so the edge accepts it.
 *
 * <p>Each direction is a token bucket with a {@link #BURST} burst, pumped by a virtual thread with blocking sockets.
 * Its receive buffers are kept small, so bytes back up in the sender rather than in the proxy's kernel buffers, and the
 * throttle bites from the first second.
 */
final class ThrottlingProxy implements AutoCloseable {

    static final int BURST = 64 * 1024;

    private static final int CHUNK = 16 * 1024;
    private static final int RECEIVE_BUFFER = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ServerSocket server;
    private final int targetPort;
    private final long bytesPerSecond;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    private ThrottlingProxy(ServerSocket server, int targetPort, long bytesPerSecond) {
        this.server = server;
        this.targetPort = targetPort;
        this.bytesPerSecond = bytesPerSecond;
        Thread.ofVirtual().name("throttling-proxy-" + server.getLocalPort()).start(this::acceptLoop);
    }

    /** Forwards to {@code targetPort} on loopback at {@code bytesPerSecond} each way. */
    static ThrottlingProxy start(int targetPort, long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("The rate must be positive: " + bytesPerSecond);
        }
        try {
            ServerSocket server = new ServerSocket();
            server.setReceiveBufferSize(RECEIVE_BUFFER);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return new ThrottlingProxy(server, targetPort, bytesPerSecond);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen for the proxy", e);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        closing = true;
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    private void acceptLoop() {
        while (!closing) {
            Socket downstream;
            try {
                downstream = server.accept();
            } catch (IOException e) {
                return;
            }
            Thread.ofVirtual().start(() -> connect(downstream));
        }
    }

    private void connect(Socket downstream) {
        Socket upstream = new Socket();
        sockets.add(downstream);
        sockets.add(upstream);
        try {
            downstream.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            upstream.setReceiveBufferSize(RECEIVE_BUFFER);
            upstream.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), (int)
                    CONNECT_TIMEOUT.toMillis());
        } catch (IOException e) {
            closeBoth(downstream, upstream);
            return;
        }
        Thread.ofVirtual().start(() -> pump(downstream, upstream));
        Thread.ofVirtual().start(() -> pump(upstream, downstream));
    }

    /** Copies {@code from} to {@code to} at the rate, passing on a half-close; any failure closes both. */
    private void pump(Socket from, Socket to) {
        TokenBucket bucket = new TokenBucket(bytesPerSecond, BURST);
        byte[] buffer = new byte[CHUNK];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                bucket.take(read);
                out.write(buffer, 0, read);
            }
            to.shutdownOutput();
        } catch (IOException e) {
            closeBoth(from, to);
        }
    }

    private void closeBoth(Socket one, Socket other) {
        for (Socket socket : new Socket[] {one, other}) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already failing; the other side learns from the close.
            }
            sockets.remove(socket);
        }
    }

    /**
     * A token bucket kept as the time its debt is paid off: taking bytes moves that time forward by what they cost, and
     * waits until it is reached. Idle time builds credit up to the burst. Used by one thread only.
     */
    private static final class TokenBucket {

        private final double nanosPerByte;
        private final long burstNanos;
        private long paidUntil = System.nanoTime();

        TokenBucket(long bytesPerSecond, int burst) {
            this.nanosPerByte = 1e9 / bytesPerSecond;
            this.burstNanos = (long) (burst * nanosPerByte);
        }

        void take(int bytes) throws InterruptedIOException {
            long now = System.nanoTime();
            paidUntil = Math.max(paidUntil, now - burstNanos) + (long) (bytes * nanosPerByte);
            long wait = paidUntil - now;
            if (wait > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted");
                }
            }
        }
    }
}
