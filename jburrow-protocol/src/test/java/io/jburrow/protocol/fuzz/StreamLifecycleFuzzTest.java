package io.jburrow.protocol.fuzz;

import static io.jburrow.protocol.stream.StreamState.CLOSED;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_LOCAL;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_REMOTE;
import static io.jburrow.protocol.stream.StreamState.OPEN;
import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamOutcome.Accept;
import io.jburrow.protocol.stream.StreamOutcome.Discard;
import io.jburrow.protocol.stream.StreamOutcome.ResetStream;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;

/**
 * Drives one {@link StreamLifecycle} through an arbitrary sequence of events.
 *
 * <p>The property: an event from the peer never throws; once {@code CLOSED}, the stream stays {@code CLOSED}; and every
 * outcome is the one the table in {@code docs/protocol.md} §9.2 gives for the state the stream was in. Events from this
 * side are only applied where the table allows them, since sending one it forbids is a bug in the caller, which
 * {@code StreamLifecycleTest} covers. Without them a stream could never reach {@code HALF_CLOSED_LOCAL}.
 */
class StreamLifecycleFuzzTest {

    private static final StreamEvent[] EVENTS = StreamEvent.values();

    @FuzzTest(maxDuration = "2m")
    void events(FuzzedDataProvider data) {
        StreamLifecycle stream = new StreamLifecycle();
        while (data.remainingBytes() > 0) {
            StreamEvent event = data.pickValue(EVENTS);
            StreamState before = stream.state();
            StreamOutcome expected = expected(before, event);
            if (expected == null) {
                continue;
            }

            StreamOutcome outcome = stream.on(event);

            assertThat(outcome).as("%s in %s", event, before).isEqualTo(expected);
            assertThat(stream.state()).as("state after %s in %s", event, before).isEqualTo(stateAfter(outcome));
        }
    }

    /** The table of {@code docs/protocol.md} §9.2, or {@code null} for an event this side may not send in {@code state}. */
    private static StreamOutcome expected(StreamState state, StreamEvent event) {
        return switch (state) {
            case OPEN ->
                switch (event) {
                    case DATA_RECEIVED, DATA_SENT, WINDOW_UPDATE_RECEIVED, WINDOW_UPDATE_SENT -> new Accept(OPEN);
                    case CLOSE_RECEIVED -> new Accept(HALF_CLOSED_REMOTE);
                    case CLOSE_SENT -> new Accept(HALF_CLOSED_LOCAL);
                    case RST_RECEIVED, RST_SENT -> new Accept(CLOSED);
                };
            case HALF_CLOSED_LOCAL ->
                switch (event) {
                    case DATA_RECEIVED, WINDOW_UPDATE_RECEIVED, WINDOW_UPDATE_SENT -> new Accept(HALF_CLOSED_LOCAL);
                    case CLOSE_RECEIVED, RST_RECEIVED, RST_SENT -> new Accept(CLOSED);
                    case DATA_SENT, CLOSE_SENT -> null;
                };
            case HALF_CLOSED_REMOTE ->
                switch (event) {
                    case DATA_RECEIVED, CLOSE_RECEIVED -> new ResetStream(ErrorCode.STREAM_CLOSED);
                    case DATA_SENT, WINDOW_UPDATE_RECEIVED -> new Accept(HALF_CLOSED_REMOTE);
                    case RST_RECEIVED, CLOSE_SENT, RST_SENT -> new Accept(CLOSED);
                    case WINDOW_UPDATE_SENT -> null;
                };
            case CLOSED -> event.isReceived() ? new Discard() : null;
        };
    }

    private static StreamState stateAfter(StreamOutcome outcome) {
        return switch (outcome) {
            case Accept accept -> accept.next();
            case ResetStream ignored -> CLOSED;
            case Discard ignored -> CLOSED;
        };
    }
}
