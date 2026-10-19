package io.jburrow.protocol.stream;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import java.util.Objects;

/** What the caller does with an event {@link StreamLifecycle} has applied. */
public sealed interface StreamOutcome {

    /** Deliver the frame, or go ahead with the send. The stream is now in {@code next}. */
    record Accept(StreamState next) implements StreamOutcome {
        public Accept {
            Objects.requireNonNull(next, "next");
        }
    }

    /**
     * The peer broke a stream rule. Send {@code CLOSE RST} with {@code code} and drop the frame. The stream is already
     * {@link StreamState#CLOSED}, so the caller must not report {@link StreamEvent#RST_SENT} for this reset.
     */
    record ResetStream(ErrorCode code) implements StreamOutcome {
        public ResetStream {
            Objects.requireNonNull(code, "code");
            if (!code.scopes().contains(ErrorScope.STREAM)) {
                throw new IllegalArgumentException(code + " is not a stream error");
            }
        }
    }

    /** The frame was in flight when the stream ended. Drop it without a reply. */
    record Discard() implements StreamOutcome {}
}
