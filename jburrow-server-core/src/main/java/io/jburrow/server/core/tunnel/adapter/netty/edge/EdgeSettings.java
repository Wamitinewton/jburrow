package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The edge's configuration, built by the composition root from {@code jburrow.edge.*}. Plain values only, so the
 * composition root needs no Netty.
 *
 * @param bind the address and port to listen on; port 0 picks an ephemeral port
 * @param connectHost the only {@code Host} the edge answers, compared without case or port
 * @param trustedProxies peers whose {@code X-Forwarded-For} is believed
 * @param maxSessions connections holding a session slot, handshaking or established
 * @param maxHandshaking connections upgraded or upgrading but not yet established
 * @param requestTimeout how long a connection may take to send its upgrade request
 * @param handshakeTimeout how long a session may take from the upgrade to its {@code REGISTER} reply
 * @param sessionWriteLow low write-buffer watermark of a session channel, in bytes
 * @param sessionWriteHigh high write-buffer watermark of a session channel, in bytes
 * @param windows the server's receive windows, announced in its {@code HELLO}
 * @param maxConcurrentStreams streams one session may have open, announced in the server {@code HELLO}
 * @param maxStreams streams open across every session; at least {@code maxConcurrentStreams}
 * @param serverVersion this build's version, announced in the server {@code HELLO}
 * @param http how HTTP tunnels are served
 * @param capabilities the capabilities the server enables; a session has those its client also offers
 */
public record EdgeSettings(
        InetSocketAddress bind,
        String connectHost,
        EdgeTransport transport,
        int ioThreads,
        List<Cidr> trustedProxies,
        int maxSessions,
        int maxHandshaking,
        Duration requestTimeout,
        Duration handshakeTimeout,
        Duration pingInterval,
        Duration idleTimeout,
        int sessionWriteLow,
        int sessionWriteHigh,
        Windows windows,
        int maxConcurrentStreams,
        int maxStreams,
        String serverVersion,
        HttpSettings http,
        Set<Capability> capabilities) {

    public EdgeSettings {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(connectHost, "connectHost");
        Objects.requireNonNull(transport, "transport");
        trustedProxies = List.copyOf(trustedProxies);
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(serverVersion, "serverVersion");
        Objects.requireNonNull(http, "http");
        capabilities = Set.copyOf(capabilities);
        if (bind.isUnresolved()) {
            throw new IllegalArgumentException("The edge binds an IP address, not a name: " + bind);
        }
        if (connectHost.isBlank()) {
            throw new IllegalArgumentException("connectHost must not be blank");
        }
        requirePositive(ioThreads, "ioThreads");
        requirePositive(maxSessions, "maxSessions");
        requirePositive(maxHandshaking, "maxHandshaking");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(handshakeTimeout, "handshakeTimeout");
        requirePositive(pingInterval, "pingInterval");
        requirePositive(idleTimeout, "idleTimeout");
        requireWatermarks(sessionWriteLow, sessionWriteHigh, "sessionWrite");
        requirePositive(maxConcurrentStreams, "maxConcurrentStreams");
        if (maxStreams < maxConcurrentStreams) {
            throw new IllegalArgumentException(
                    "maxStreams must be at least maxConcurrentStreams: " + maxStreams + " < " + maxConcurrentStreams);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (Objects.requireNonNull(value, name).isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    private static void requireWatermarks(int low, int high, String name) {
        if (low < 1 || low >= high) {
            throw new IllegalArgumentException(name + " watermarks must satisfy 0 < low < high: " + low + "-" + high);
        }
    }
}
