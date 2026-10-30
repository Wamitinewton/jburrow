package io.jburrow.server.core.tunnel.adapter.netty.edge;

import java.time.Duration;
import java.util.Objects;

/**
 * How the edge serves HTTP tunnels, built by the composition root from {@code jburrow.http.*}.
 *
 * @param domain tunnels are {@code <name>.<domain>}; lower-case
 * @param maxRequestBody the largest request body the edge forwards, in bytes
 * @param responseTimeout how long the edge waits from {@code OPEN} to the final response head
 * @param keepAliveTimeout how long an edge connection may sit idle between exchanges
 * @param socketBuffer {@code SO_SNDBUF} and {@code SO_RCVBUF} of an edge connection that carries exchanges, in bytes,
 *     or {@link #KERNEL_SIZED}
 * @param parkAfter how long a stalled response waits before its buffered bytes are parked
 * @param maxParked bytes parked across the server; 0 turns parking off
 * @param maxParkedPerSession bytes one session may have parked; at most {@code maxParked}
 */
public record HttpSettings(
        String domain,
        long maxRequestBody,
        Duration responseTimeout,
        Duration keepAliveTimeout,
        int socketBuffer,
        Duration parkAfter,
        long maxParked,
        long maxParkedPerSession) {

    /** Leaves an edge connection's socket buffers to the kernel. */
    public static final int KERNEL_SIZED = 0;

    public HttpSettings {
        Objects.requireNonNull(domain, "domain");
        if (domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (maxRequestBody < 1) {
            throw new IllegalArgumentException("maxRequestBody must be positive: " + maxRequestBody);
        }
        requirePositive(responseTimeout, "responseTimeout");
        requirePositive(keepAliveTimeout, "keepAliveTimeout");
        requirePositive(parkAfter, "parkAfter");
        if (socketBuffer < 0) {
            throw new IllegalArgumentException("socketBuffer must not be negative: " + socketBuffer);
        }
        if (maxParked < 0 || maxParkedPerSession < 0 || maxParkedPerSession > maxParked) {
            throw new IllegalArgumentException("Parking limits must satisfy 0 <= maxParkedPerSession <= maxParked: "
                    + maxParkedPerSession + ", " + maxParked);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (Objects.requireNonNull(value, name).isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
