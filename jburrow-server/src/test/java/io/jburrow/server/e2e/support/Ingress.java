package io.jburrow.server.e2e.support;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Talks to the edge as the ingress controller does: a small pool of keep-alive connections, each carrying one request
 * at a time for whichever host comes next, so requests for different tunnels share connections. A connection the edge
 * closes is not replaced, so a test that expects every connection to stay open finds out if one didn't.
 *
 * <p>Safe for as many threads as it has connections; more wait for a free one.
 */
public final class Ingress implements AutoCloseable {

    private final List<HttpVisitor> connections = new ArrayList<>();
    private final BlockingQueue<HttpVisitor> free;

    private Ingress(int size) {
        this.free = new ArrayBlockingQueue<>(size);
    }

    /** {@code size} connections to the edge on loopback {@code port}. */
    public static Ingress connect(int port, int size, Duration timeout) throws IOException {
        Ingress ingress = new Ingress(size);
        try {
            for (int i = 0; i < size; i++) {
                HttpVisitor connection = HttpVisitor.connect(port, timeout).asIngress();
                ingress.connections.add(connection);
                ingress.free.add(connection);
            }
            return ingress;
        } catch (IOException e) {
            ingress.close();
            throw e;
        }
    }

    /** {@code GET target} on {@code host}, on the next free connection, read whole. */
    public HttpVisitor.Response get(String host, String target) throws IOException, InterruptedException {
        HttpVisitor connection = free.take();
        try {
            connection.request("GET", host, target);
            return connection.response();
        } finally {
            free.add(connection);
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (HttpVisitor connection : connections) {
            try {
                connection.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
