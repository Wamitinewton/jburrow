package io.jburrow.protocol.control;

import io.jburrow.protocol.flow.FlowLimits;

/**
 * A side's receive windows, announced in its {@code HELLO}: the credit its peer starts with on each new stream and on
 * the session as a whole. Fixed for the session.
 */
public record Windows(int stream, int session) {

    public Windows {
        Fields.range(stream, FlowLimits.MIN_STREAM_WINDOW, FlowLimits.MAX_WINDOW, "stream");
        if (session < stream) {
            throw Fields.invalid("session", "must be at least stream");
        }
    }
}
