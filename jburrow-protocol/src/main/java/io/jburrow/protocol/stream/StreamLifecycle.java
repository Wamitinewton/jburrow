package io.jburrow.protocol.stream;

import static io.jburrow.protocol.stream.StreamState.CLOSED;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_LOCAL;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_REMOTE;
import static io.jburrow.protocol.stream.StreamState.OPEN;

import io.jburrow.protocol.stream.StreamOutcome.Accept;
import io.jburrow.protocol.stream.StreamOutcome.Discard;
import io.jburrow.protocol.stream.StreamOutcome.ResetStream;
import io.jburrow.protocol.wire.ErrorCode;
import java.util.Objects;

/**
 * The state of one stream that exists on its session. A stream starts {@link StreamState#OPEN}: the server creates it
 * when it sends {@code OPEN}, the client when it receives one. Whether an id was ever opened is the session's check,
 * made before it looks the stream up.
 *
 * <p>An event from the peer never throws: a frame the stream can't take yields {@link ResetStream} or
 * {@link Discard}. An event from this side that the state forbids is a bug in the caller and throws
 * {@link IllegalStateException}.
 *
 * <p>Not thread-safe. Confined to the thread that owns its session.
 */
public final class StreamLifecycle {

    private static final Discard DISCARD = new Discard();
    private static final ResetStream RESET_STREAM_CLOSED = new ResetStream(ErrorCode.STREAM_CLOSED);
    private static final Accept[] ACCEPT = new Accept[StreamState.values().length];

    static {
        for (StreamState next : StreamState.values()) {
            ACCEPT[next.ordinal()] = new Accept(next);
        }
    }

    private StreamState state = OPEN;

    public StreamState state() {
        return state;
    }

    public boolean isClosed() {
        return state == CLOSED;
    }

    /** Applies {@code event} and returns what the caller does with it. */
    public StreamOutcome on(StreamEvent event) {
        Objects.requireNonNull(event, "event");
        StreamOutcome outcome =
                switch (state) {
                    case OPEN ->
                        switch (event) {
                            case DATA_RECEIVED, DATA_SENT -> accept(OPEN);
                            case CLOSE_RECEIVED -> accept(HALF_CLOSED_REMOTE);
                            case CLOSE_SENT -> accept(HALF_CLOSED_LOCAL);
                            case RST_RECEIVED, RST_SENT -> accept(CLOSED);
                        };
                    case HALF_CLOSED_LOCAL ->
                        switch (event) {
                            case DATA_RECEIVED -> accept(HALF_CLOSED_LOCAL);
                            case CLOSE_RECEIVED, RST_RECEIVED, RST_SENT -> accept(CLOSED);
                            case DATA_SENT, CLOSE_SENT -> throw forbidden(event);
                        };
                    case HALF_CLOSED_REMOTE ->
                        switch (event) {
                            case DATA_RECEIVED, CLOSE_RECEIVED -> RESET_STREAM_CLOSED;
                            case DATA_SENT -> accept(HALF_CLOSED_REMOTE);
                            case RST_RECEIVED, CLOSE_SENT, RST_SENT -> accept(CLOSED);
                        };
                    case CLOSED -> {
                        if (!event.isReceived()) {
                            throw forbidden(event);
                        }
                        yield DISCARD;
                    }
                };
        if (outcome instanceof ResetStream) {
            state = CLOSED;
        }
        return outcome;
    }

    private Accept accept(StreamState next) {
        state = next;
        return ACCEPT[next.ordinal()];
    }

    private IllegalStateException forbidden(StreamEvent event) {
        return new IllegalStateException(event + " is not allowed on a stream in state " + state);
    }
}
