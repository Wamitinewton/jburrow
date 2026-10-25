package io.jburrow.client.tunnel.domain;

import java.util.Objects;

/**
 * The address on the client's side a tunnel forwards to: a host name or IP literal, and a port. Only the client knows
 * it; it is never sent to the server.
 *
 * @param host a host name, or an IP literal; an IPv6 literal without brackets
 * @param port 1 to 65,535
 */
public record LocalTarget(String host, int port) {

    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_PORT = 65_535;

    public LocalTarget {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("The local host must not be blank");
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("The local host must be at most " + MAX_HOST_LENGTH + " characters");
        }
        if (host.contains("/")
                || host.contains("[")
                || host.contains("]")
                || host.chars().anyMatch(c -> c <= ' ')) {
            throw new IllegalArgumentException(
                    "The local host must be a bare host name or IP address, without a scheme, path or brackets");
        }
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("The local port must be between 1 and " + MAX_PORT + ": " + port);
        }
    }

    public static LocalTarget of(String host, int port) {
        return new LocalTarget(host, port);
    }

    /** {@code host:port}, with an IPv6 literal in brackets. */
    @Override
    public String toString() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
