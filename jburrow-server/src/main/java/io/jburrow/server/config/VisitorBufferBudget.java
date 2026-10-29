package io.jburrow.server.config;

import java.util.Locale;

/**
 * The most kernel memory visitors held back by credit can park in their receive buffers, outside the JVM but charged
 * to the pod ({@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Memory budget). A visitor without credit isn't read,
 * so what it sends waits in its socket's receive buffer, which Linux sizes at twice the {@code SO_RCVBUF} asked for.
 *
 * @param streams streams open across every session
 * @param receiveBuffer the {@code SO_RCVBUF} asked for, in bytes; 0 when the kernel sizes each buffer itself
 */
record VisitorBufferBudget(int streams, long receiveBuffer) {

    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;

    VisitorBufferBudget {
        if (streams < 0 || receiveBuffer < 0) {
            throw new IllegalArgumentException("A budget can't be negative");
        }
    }

    static VisitorBufferBudget of(JBurrowProperties.Edge edge, JBurrowProperties.Tcp tcp) {
        return new VisitorBufferBudget(
                edge.maxStreams(), tcp.visitorReceiveBuffer().toBytes());
    }

    /** Whether nothing in the configuration bounds a visitor's buffer. */
    boolean kernelSized() {
        return receiveBuffer == 0;
    }

    /** What one visitor's receive buffer holds at most: Linux doubles {@code SO_RCVBUF} for its own bookkeeping. */
    long perStream() {
        return 2 * receiveBuffer;
    }

    long total() {
        return perStream() * streams;
    }

    /** For example {@code 4000 streams × 64 KiB = 250 MiB of kernel memory}. */
    String describe() {
        if (kernelSized()) {
            return String.format(
                    Locale.ROOT,
                    "%d streams × the kernel's sizing (net.ipv4.tcp_rmem, 128 KiB by default and more for a fast"
                            + " visitor), unbounded by jburrow.tcp.visitor-receive-buffer",
                    streams);
        }
        return String.format(
                Locale.ROOT,
                "%d streams × %d KiB = %d MiB of kernel memory",
                streams,
                perStream() / KIB,
                Math.round((double) total() / MIB));
    }
}
