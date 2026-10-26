package io.jburrow.protocol.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReceiveWindowTest {

    @Test
    void startsWithTheWholeWindowRemaining() {
        ReceiveWindow window = new ReceiveWindow("stream", 65_536);

        assertThat(window.size()).isEqualTo(65_536);
        assertThat(window.remaining()).isEqualTo(65_536);
        assertThat(window.held()).isZero();
        assertThat(window.unreturned()).isZero();
        assertThat(window.isReturning()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void aWindowIsAtLeastOneByte(int size) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReceiveWindow("stream", size));
    }

    @Test
    void receivesUpToExactlyTheWindow() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);

        window.receive(3);
        window.receive(7);

        assertThat(window.remaining()).isZero();
        assertThat(window.held()).isEqualTo(10);
        assertInvariant(window);
    }

    @Test
    void oneBytePastTheWindowIsAFlowControlErrorAndChangesNothing() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(7);

        assertThatThrownBy(() -> window.receive(4))
                .isInstanceOfSatisfying(ProtocolException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR);
                    assertThat(e.scope()).isEqualTo(ErrorScope.SESSION);
                })
                .hasMessage("DATA of 4 bytes overruns the stream window, which has 3 bytes remaining");
        assertThat(window.remaining()).isEqualTo(3);
        assertThat(window.held()).isEqualTo(7);
        assertInvariant(window);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void dataCarriesAtLeastOneByte(int bytes) {
        ReceiveWindow window = new ReceiveWindow("stream", 10);

        assertThatIllegalArgumentException().isThrownBy(() -> window.receive(bytes));
        assertThat(window.remaining()).isEqualTo(10);
    }

    /**
     * Window size, the releases made after receiving the whole window, and the increment each returns. The threshold is
     * half the window, rounded down.
     */
    static Stream<Arguments> thresholds() {
        return Stream.of(
                arguments(10, List.of(1, 1, 1, 1, 1, 5), List.of(0, 0, 0, 0, 5, 5)),
                arguments(10, List.of(4, 6), List.of(0, 10)),
                arguments(10, List.of(10), List.of(10)),
                arguments(11, List.of(4, 1, 6), List.of(0, 5, 6)),
                arguments(11, List.of(4, 7), List.of(0, 11)),
                arguments(3, List.of(1, 1, 1), List.of(1, 1, 1)),
                arguments(2, List.of(1, 1), List.of(1, 1)),
                arguments(1, List.of(1), List.of(1)),
                arguments(65_536, List.of(32_767, 1, 32_768), List.of(0, 32_768, 32_768)),
                arguments(262_144, List.of(131_071, 131_073), List.of(0, 262_144)));
    }

    @ParameterizedTest(name = "window {0}, releases {1} -> {2}")
    @MethodSource("thresholds")
    void returnsTheWholeUnreturnedAmountOnceItReachesHalfTheWindow(
            int size, List<Integer> releases, List<Integer> expected) {
        ReceiveWindow window = new ReceiveWindow("stream", size);
        window.receive(size);

        List<Integer> increments = new ArrayList<>();
        for (int bytes : releases) {
            increments.add(window.release(bytes));
            assertInvariant(window);
        }

        assertThat(increments).isEqualTo(expected);
    }

    @Test
    void anIncrementGoesBackIntoTheRemainingWindow() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(10);

        assertThat(window.release(6)).isEqualTo(6);

        assertThat(window.remaining()).isEqualTo(6);
        assertThat(window.held()).isEqualTo(4);
        assertThat(window.unreturned()).isZero();
    }

    @Test
    void releasingMoreThanIsHeldIsABugAndChangesNothing() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(5);
        window.release(2);

        assertThatIllegalStateException()
                .isThrownBy(() -> window.release(4))
                .withMessage("Releasing 4 bytes from the stream window, which holds 3");
        assertThat(window.held()).isEqualTo(3);
        assertThat(window.unreturned()).isEqualTo(2);
        assertInvariant(window);
    }

    @Test
    void aDoubleReleaseThrows() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(5);
        window.release(5);

        assertThatIllegalStateException().isThrownBy(() -> window.release(5));
        assertThat(window.held()).isZero();
        assertInvariant(window);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aReleaseIsAtLeastOneByte(int bytes) {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(5);

        assertThatIllegalArgumentException().isThrownBy(() -> window.release(bytes));
        assertThat(window.held()).isEqualTo(5);
    }

    @Test
    void stopReturningStopsIncrementsButNotTheAccounting() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(10);

        window.stopReturning();

        assertThat(window.isReturning()).isFalse();
        assertThat(window.release(6)).isZero();
        assertThat(window.release(4)).isZero();
        assertThat(window.held()).isZero();
        assertThat(window.unreturned()).isEqualTo(10);
        assertThat(window.remaining()).isZero();
        assertInvariant(window);
    }

    @Test
    void stopReturningKeepsWhatWasAlreadyReturned() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);
        window.receive(10);
        assertThat(window.release(5)).isEqualTo(5);

        window.stopReturning();

        assertThat(window.remaining()).isEqualTo(5);
        assertThat(window.release(5)).isZero();
        assertInvariant(window);
    }

    @Test
    void creditIsNeverReturnedForBytesStillHeld() {
        ReceiveWindow window = new ReceiveWindow("stream", 10);

        window.receive(10);

        assertThat(window.remaining()).isZero();
        assertThat(window.unreturned()).isZero();
    }

    private static void assertInvariant(ReceiveWindow window) {
        assertThat((long) window.remaining() + window.held() + window.unreturned())
                .as("remaining + held + unreturned of %s", window)
                .isEqualTo(window.size());
    }
}
