package io.jburrow.server.core.tunnel.adapter.netty.edge;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

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
 * @param visitorWriteLow low write-buffer watermark of a visitor channel, in bytes
 * @param visitorWriteHigh high write-buffer watermark of a visitor channel, in bytes
 * @param serverVersion this build's version, announced in the server {@code HELLO}
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
        int visitorWriteLow,
        int visitorWriteHigh,
        String serverVersion) {

    public EdgeSettings {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(connectHost, "connectHost");
        Objects.requireNonNull(transport, "transport");
        trustedProxies = List.copyOf(trustedProxies);
        Objects.requireNonNull(serverVersion, "serverVersion");
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
        requireWatermarks(visitorWriteLow, visitorWriteHigh, "visitorWrite");
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
