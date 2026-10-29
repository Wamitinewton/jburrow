package io.jburrow.protocol.flow;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import java.util.Objects;

/** What a received {@code DATA} frame means, once its stream's lifecycle and the windows have seen it. */
public sealed interface DataOutcome {

    /** Shared, since most frames are delivered and the byte path shouldn't allocate for them. */
    Deliver DELIVER = new Deliver();

    /** Write the payload to its destination, and release its bytes when the write completes or fails. */
    record Deliver() implements DataOutcome {}

    /**
     * The peer broke a stream rule: drop the payload, send {@code credit}, then {@code CLOSE RST} with {@code code}.
     * The bytes are already released and the stream is already closed.
     */
    record Reset(ErrorCode code, Credit credit) implements DataOutcome {
        public Reset {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(credit, "credit");
            if (!code.scopes().contains(ErrorScope.STREAM)) {
                throw new IllegalArgumentException(code + " is not a stream error");
            }
        }
    }

    /** The stream had closed: drop the payload and send {@code credit}. The bytes are already released. */
    record Discard(Credit credit) implements DataOutcome {
        public Discard {
            Objects.requireNonNull(credit, "credit");
        }
    }
}
