package io.jburrow.server.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final int COPY_BUFFER = 64 * 1024;

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
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
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
