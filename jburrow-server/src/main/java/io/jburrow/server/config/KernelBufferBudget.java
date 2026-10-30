package io.jburrow.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The most kernel memory streams held back by credit can park in their sockets' buffers, outside the JVM but charged to
 * the pod ({@code docs/phase-2-multiplexing/ARCHITECTURE.md} and {@code docs/phase-3-http-tunnels/ARCHITECTURE.md}
 * §Memory budget). A TCP visitor without credit isn't read, so what it sends waits in its receive buffer; an HTTP
 * exchange can hold a held-back upload in its edge connection's receive buffer and a stalled response in its send
 * buffer. A stream is one or the other, and Linux sizes each buffer at twice what is asked for.
 *
 * @param streams streams open across every session
 * @param visitorReceiveBuffer a TCP visitor's {@code SO_RCVBUF}, in bytes; 0 when the kernel sizes it
 * @param exchangeSocketBuffer an exchange's {@code SO_SNDBUF} and {@code SO_RCVBUF} each, in bytes; 0 when the kernel
 *     sizes them
 */
record KernelBufferBudget(int streams, long visitorReceiveBuffer, long exchangeSocketBuffer) {

    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;

    KernelBufferBudget {
        if (streams < 0 || visitorReceiveBuffer < 0 || exchangeSocketBuffer < 0) {
            throw new IllegalArgumentException("A budget can't be negative");
        }
    }

    static KernelBufferBudget of(JBurrowProperties.Edge edge, JBurrowProperties.Tcp tcp, JBurrowProperties.Http http) {
        return new KernelBufferBudget(
                edge.maxStreams(),
                tcp.visitorReceiveBuffer().toBytes(),
                http.socketBuffer().toBytes());
    }

    /** Whether nothing in the configuration bounds some stream's buffers. */
    boolean kernelSized() {
        return visitorReceiveBuffer == 0 || exchangeSocketBuffer == 0;
    }

    /** What one stream's buffers hold at most, a visitor's or an exchange's, whichever is larger. */
    long perStream() {
        return Math.max(2 * visitorReceiveBuffer, 2 * 2 * exchangeSocketBuffer);
    }

    long total() {
        return perStream() * streams;
    }

    /**
     * For example {@code 4000 streams × 64 KiB (a TCP visitor's receive buffer, or an exchange's send and receive
     * buffers) = 250 MiB of kernel memory}.
     */
    String describe() {
        if (kernelSized()) {
            List<String> unbounded = new ArrayList<>(2);
            if (visitorReceiveBuffer == 0) {
                unbounded.add("jburrow.tcp.visitor-receive-buffer");
            }
            if (exchangeSocketBuffer == 0) {
                unbounded.add("jburrow.http.socket-buffer");
            }
            return String.format(
                    Locale.ROOT,
                    "%d streams × the kernel's sizing (net.ipv4.tcp_rmem and tcp_wmem, which grow with a fast peer),"
                            + " unbounded by %s",
                    streams,
                    String.join(" and ", unbounded));
        }
        return String.format(
                Locale.ROOT,
                "%d streams × %d KiB (a TCP visitor's receive buffer, or an exchange's send and receive buffers)"
                        + " = %d MiB of kernel memory",
                streams,
                perStream() / KIB,
                Math.round((double) total() / MIB));
    }
}
