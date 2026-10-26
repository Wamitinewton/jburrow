package io.jburrow.protocol.stream;

import static io.jburrow.protocol.stream.StreamEvent.CLOSE_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.CLOSE_SENT;
import static io.jburrow.protocol.stream.StreamEvent.DATA_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.DATA_SENT;
import static io.jburrow.protocol.stream.StreamEvent.RST_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.RST_SENT;
import static io.jburrow.protocol.stream.StreamEvent.WINDOW_UPDATE_RECEIVED;
import static io.jburrow.protocol.stream.StreamEvent.WINDOW_UPDATE_SENT;
import static io.jburrow.protocol.stream.StreamState.CLOSED;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_LOCAL;
import static io.jburrow.protocol.stream.StreamState.HALF_CLOSED_REMOTE;
import static io.jburrow.protocol.stream.StreamState.OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.stream.StreamOutcome.Accept;
import io.jburrow.protocol.stream.StreamOutcome.Discard;
import io.jburrow.protocol.stream.StreamOutcome.ResetStream;
import io.jburrow.protocol.wire.ErrorCode;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StreamLifecycleTest {

    private static final StreamOutcome RESET = new ResetStream(ErrorCode.STREAM_CLOSED);
    private static final StreamOutcome DISCARD = new Discard();

    /** One row per cell of {@code docs/protocol.md} §9.2; {@code null} means the event throws. */
    static Stream<Arguments> table() {
        return Stream.of(
                arguments(OPEN, DATA_RECEIVED, accept(OPEN)),
                arguments(OPEN, CLOSE_RECEIVED, accept(HALF_CLOSED_REMOTE)),
                arguments(OPEN, RST_RECEIVED, accept(CLOSED)),
                arguments(OPEN, DATA_SENT, accept(OPEN)),
                arguments(OPEN, CLOSE_SENT, accept(HALF_CLOSED_LOCAL)),
                arguments(OPEN, RST_SENT, accept(CLOSED)),
                arguments(OPEN, WINDOW_UPDATE_RECEIVED, accept(OPEN)),
                arguments(OPEN, WINDOW_UPDATE_SENT, accept(OPEN)),
                arguments(HALF_CLOSED_LOCAL, DATA_RECEIVED, accept(HALF_CLOSED_LOCAL)),
                arguments(HALF_CLOSED_LOCAL, CLOSE_RECEIVED, accept(CLOSED)),
                arguments(HALF_CLOSED_LOCAL, RST_RECEIVED, accept(CLOSED)),
                arguments(HALF_CLOSED_LOCAL, DATA_SENT, null),
                arguments(HALF_CLOSED_LOCAL, CLOSE_SENT, null),
                arguments(HALF_CLOSED_LOCAL, RST_SENT, accept(CLOSED)),
                arguments(HALF_CLOSED_LOCAL, WINDOW_UPDATE_RECEIVED, accept(HALF_CLOSED_LOCAL)),
                arguments(HALF_CLOSED_LOCAL, WINDOW_UPDATE_SENT, accept(HALF_CLOSED_LOCAL)),
                arguments(HALF_CLOSED_REMOTE, DATA_RECEIVED, RESET),
                arguments(HALF_CLOSED_REMOTE, CLOSE_RECEIVED, RESET),
                arguments(HALF_CLOSED_REMOTE, RST_RECEIVED, accept(CLOSED)),
                arguments(HALF_CLOSED_REMOTE, DATA_SENT, accept(HALF_CLOSED_REMOTE)),
                arguments(HALF_CLOSED_REMOTE, CLOSE_SENT, accept(CLOSED)),
                arguments(HALF_CLOSED_REMOTE, RST_SENT, accept(CLOSED)),
                arguments(HALF_CLOSED_REMOTE, WINDOW_UPDATE_RECEIVED, accept(HALF_CLOSED_REMOTE)),
                arguments(HALF_CLOSED_REMOTE, WINDOW_UPDATE_SENT, null),
                arguments(CLOSED, DATA_RECEIVED, DISCARD),
                arguments(CLOSED, CLOSE_RECEIVED, DISCARD),
                arguments(CLOSED, RST_RECEIVED, DISCARD),
                arguments(CLOSED, DATA_SENT, null),
                arguments(CLOSED, CLOSE_SENT, null),
                arguments(CLOSED, RST_SENT, null),
                arguments(CLOSED, WINDOW_UPDATE_RECEIVED, DISCARD),
                arguments(CLOSED, WINDOW_UPDATE_SENT, null));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @MethodSource("table")
    void appliesTheTransitionTable(StreamState from, StreamEvent event, StreamOutcome expected) {
        StreamLifecycle stream = in(from);

        if (expected == null) {
            assertThatIllegalStateException().isThrownBy(() -> stream.on(event));
            assertThat(stream.state()).isEqualTo(from);
            return;
        }
        assertThat(stream.on(event)).isEqualTo(expected);
        StreamState after = expected instanceof Accept accept ? accept.next() : CLOSED;
        assertThat(stream.state()).isEqualTo(after);
        assertThat(stream.isClosed()).isEqualTo(after == CLOSED);
    }

    @Test
    void tableCoversEveryStateAndEvent() {
        Set<String> cells =
                table().map(row -> row.get()[0] + "/" + row.get()[1]).collect(Collectors.toSet());

        assertThat(cells).hasSize(StreamState.values().length * StreamEvent.values().length);
    }

    @Test
    void peerEventsNeverThrow() {
        for (StreamState from : StreamState.values()) {
            for (StreamEvent event : StreamEvent.values()) {
                if (event.isReceived()) {
                    assertThat(in(from).on(event)).isNotNull();
                }
            }
        }
    }

    @Test
    void startsOpen() {
        StreamLifecycle stream = new StreamLifecycle();

        assertThat(stream.state()).isEqualTo(OPEN);
        assertThat(stream.isClosed()).isFalse();
    }

    @Test
    void visitorHalfClosesFirstThenTheLocalService() {
        StreamLifecycle server = new StreamLifecycle();

        assertThat(server.on(DATA_SENT)).isEqualTo(accept(OPEN));
        assertThat(server.on(CLOSE_SENT)).isEqualTo(accept(HALF_CLOSED_LOCAL));
        assertThat(server.on(DATA_RECEIVED)).isEqualTo(accept(HALF_CLOSED_LOCAL));
        assertThat(server.on(CLOSE_RECEIVED)).isEqualTo(accept(CLOSED));
    }

    @Test
    void localServiceHalfClosesFirstThenTheVisitor() {
        StreamLifecycle server = new StreamLifecycle();

        assertThat(server.on(CLOSE_RECEIVED)).isEqualTo(accept(HALF_CLOSED_REMOTE));
        assertThat(server.on(DATA_SENT)).isEqualTo(accept(HALF_CLOSED_REMOTE));
        assertThat(server.on(CLOSE_SENT)).isEqualTo(accept(CLOSED));
    }

    @Test
    void creditKeepsFlowingToASideThatHasFinishedSending() {
        StreamLifecycle server = new StreamLifecycle();

        assertThat(server.on(CLOSE_SENT)).isEqualTo(accept(HALF_CLOSED_LOCAL));
        assertThat(server.on(DATA_RECEIVED)).isEqualTo(accept(HALF_CLOSED_LOCAL));
        assertThat(server.on(WINDOW_UPDATE_SENT)).isEqualTo(accept(HALF_CLOSED_LOCAL));
        assertThat(server.on(WINDOW_UPDATE_RECEIVED)).isEqualTo(accept(HALF_CLOSED_LOCAL));
    }

    @Test
    void noCreditGoesBackOnceThePeerHasFinishedSending() {
        StreamLifecycle server = new StreamLifecycle();
        server.on(CLOSE_RECEIVED);

        assertThatIllegalStateException().isThrownBy(() -> server.on(WINDOW_UPDATE_SENT));
        assertThat(server.state()).isEqualTo(HALF_CLOSED_REMOTE);
        assertThat(server.on(WINDOW_UPDATE_RECEIVED)).isEqualTo(accept(HALF_CLOSED_REMOTE));
        assertThat(server.on(DATA_SENT)).isEqualTo(accept(HALF_CLOSED_REMOTE));
    }

    @Test
    void resetStreamLeavesTheStreamClosed() {
        StreamLifecycle stream = in(HALF_CLOSED_REMOTE);

        assertThat(stream.on(DATA_RECEIVED)).isEqualTo(RESET);
        assertThat(stream.isClosed()).isTrue();
        assertThat(stream.on(DATA_RECEIVED)).isEqualTo(DISCARD);
        assertThatIllegalStateException().isThrownBy(() -> stream.on(RST_SENT));
    }

    @Test
    void resetStreamCarriesOnlyStreamErrors() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ResetStream(ErrorCode.HEARTBEAT_TIMEOUT));
        assertThat(new ResetStream(ErrorCode.INTERNAL_ERROR).code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void receivedEventsAreExactlyThePeersOnes() {
        Set<StreamEvent> received = EnumSet.noneOf(StreamEvent.class);
        for (StreamEvent event : StreamEvent.values()) {
            if (event.isReceived()) {
                received.add(event);
            }
        }

        assertThat(received)
                .containsExactlyInAnyOrder(DATA_RECEIVED, CLOSE_RECEIVED, RST_RECEIVED, WINDOW_UPDATE_RECEIVED);
    }

    private static Accept accept(StreamState next) {
        return new Accept(next);
    }

    private static StreamLifecycle in(StreamState state) {
        StreamLifecycle stream = new StreamLifecycle();
        switch (state) {
            case OPEN -> {}
            case HALF_CLOSED_LOCAL -> stream.on(CLOSE_SENT);
            case HALF_CLOSED_REMOTE -> stream.on(CLOSE_RECEIVED);
            case CLOSED -> stream.on(RST_SENT);
        }
        assertThat(stream.state()).isEqualTo(state);
        return stream;
    }
}
