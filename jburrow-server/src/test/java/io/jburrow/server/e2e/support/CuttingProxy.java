package io.jburrow.server.e2e.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP proxy on loopback that forwards every connection to a port, until a test breaks the path the way a network
 * does: {@link #cut()} resets every connection at once, as a dropped Wi-Fi link or an ingress reload does;
 * {@link #blackhole} keeps connections open and swallows what crosses them, as a laptop that changed networks leaves
 * its old connection; {@link #refuse()} resets each new connection as it arrives, as a port nothing listens on does.
 *
 * <p>Breaking the path never touches connections made after it: they are forwarded as usual, unless the proxy refuses.
 *
 * <p>Its threads are daemon platform threads, as {@code ThrottlingProxy}'s are.
 */
public final class CuttingProxy implements AutoCloseable {

    /** Which way bytes cross a connection. */
    public enum Direction {
        /** From whoever connected to the proxy towards the target. */
        TO_TARGET,
        /** From the target back to whoever connected. */
        FROM_TARGET
    }

    private static final int CHUNK = 16 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ServerSocket server;
    private final int targetPort;
    private final Set<Link> links = ConcurrentHashMap.newKeySet();
    private volatile boolean refusing;
    private volatile boolean closing;

    private CuttingProxy(ServerSocket server, int targetPort) {
        this.server = server;
        this.targetPort = targetPort;
        thread("cutting-proxy-" + server.getLocalPort()).start(this::acceptLoop);
    }

    /** Forwards loopback connections on a port of the system's choosing to {@code targetPort}. */
    public static CuttingProxy to(int targetPort) {
        return on(0, targetPort);
    }

    /** Forwards loopback connections on {@code port} to {@code targetPort}. */
    public static CuttingProxy on(int port, int targetPort) {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return new CuttingProxy(server, targetPort);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen for the proxy on " + port, e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Connections forwarded now. */
    public int connections() {
        return links.size();
    }

    /** Resets every connection in both directions at once; new ones are forwarded. */
    public void cut() {
        for (Link link : links) {
            link.reset();
        }
    }

    /** Swallows, from now on, everything crossing the connections open now in every direction given, and keeps them open. */
    public void blackhole(Direction... directions) {
        for (Link link : links) {
            for (Direction direction : directions) {
                link.drop(direction);
            }
        }
    }

    /** Resets each new connection as soon as it is accepted, until {@link #forward()}. */
    public void refuse() {
        refusing = true;
    }

    /** Forwards new connections again. */
    public void forward() {
        refusing = false;
    }

    @Override
    public void close() throws IOException {
        closing = true;
        server.close();
        for (Link link : links) {
            link.reset();
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
            if (refusing) {
                reset(downstream);
            } else {
                thread("cutting-proxy-connect").start(() -> connect(downstream));
            }
        }
    }

    private void connect(Socket downstream) {
        Socket upstream = new Socket();
        try {
            downstream.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            upstream.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), (int)
                    CONNECT_TIMEOUT.toMillis());
        } catch (IOException e) {
            reset(downstream);
            reset(upstream);
            return;
        }
        Link link = new Link(downstream, upstream);
        links.add(link);
        if (closing) {
            link.reset();
            return;
        }
        thread("cutting-proxy-up").start(() -> link.pump(Direction.TO_TARGET));
        thread("cutting-proxy-down").start(() -> link.pump(Direction.FROM_TARGET));
    }

    private static Thread.Builder thread(String name) {
        return Thread.ofPlatform().daemon().name(name);
    }

    /** Closes {@code socket} with a reset rather than a FIN, so the peer sees the connection fail, not end. */
    private static void reset(Socket socket) {
        try {
            if (!socket.isClosed()) {
                socket.setSoLinger(true, 0);
            }
        } catch (IOException ignored) {
            // Closed already, or never connected: a plain close does.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    /** One forwarded connection: the socket accepted from the client and the one opened to the target. */
    private final class Link {

        private final Socket downstream;
        private final Socket upstream;
        private volatile boolean dropToTarget;
        private volatile boolean dropFromTarget;
        private final AtomicInteger ended = new AtomicInteger();

        Link(Socket downstream, Socket upstream) {
            this.downstream = downstream;
            this.upstream = upstream;
        }

        void drop(Direction direction) {
            switch (direction) {
                case TO_TARGET -> dropToTarget = true;
                case FROM_TARGET -> dropFromTarget = true;
            }
        }

        private boolean dropping(Direction direction) {
            return direction == Direction.TO_TARGET ? dropToTarget : dropFromTarget;
        }

        /** Copies one direction, passing on a half-close; a failure resets both sides. */
        void pump(Direction direction) {
            Socket from = direction == Direction.TO_TARGET ? downstream : upstream;
            Socket to = direction == Direction.TO_TARGET ? upstream : downstream;
            byte[] buffer = new byte[CHUNK];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    if (!dropping(direction)) {
                        out.write(buffer, 0, read);
                    }
                }
                if (!dropping(direction)) {
                    to.shutdownOutput();
                }
            } catch (IOException e) {
                reset();
                return;
            }
            if (ended.incrementAndGet() == 2) {
                close();
            }
        }

        /** Both directions ended with a half-close each: the connection is over. */
        private void close() {
            links.remove(this);
            for (Socket socket : new Socket[] {downstream, upstream}) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Already gone.
                }
            }
        }

        void reset() {
            links.remove(this);
            CuttingProxy.reset(downstream);
            CuttingProxy.reset(upstream);
        }
    }
}
