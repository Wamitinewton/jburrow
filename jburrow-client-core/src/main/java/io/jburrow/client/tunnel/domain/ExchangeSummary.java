package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.wire.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One finished exchange of an HTTP tunnel, as the client saw it. Never header values or bodies. {@code target} is the
 * request target as the visitor sent it, path and query included: the developer's own data, reported on the
 * developer's machine only.
 *
 * @param status the final response status, once the local service sent a final head
 * @param reset the code the stream was reset with, by either side, before the response was complete; empty when the
 *     response was complete, even if the rest of the request body was then cancelled
 * @param requestBytes request body bytes from the visitor to the local service
 * @param responseBytes response body bytes from the local service to the visitor
 * @param durationMillis from the {@code OPEN} to the exchange's end
 */
public record ExchangeSummary(
        int streamId,
        String method,
        String target,
        OptionalInt status,
        Optional<ErrorCode> reset,
        long requestBytes,
        long responseBytes,
        long durationMillis) {

    public ExchangeSummary {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reset, "reset");
    }

    /** Whether the whole response reached the server. */
    public boolean completed() {
        return reset.isEmpty();
    }
}
