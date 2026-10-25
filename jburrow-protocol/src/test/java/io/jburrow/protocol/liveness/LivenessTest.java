package io.jburrow.protocol.liveness;

import static io.jburrow.protocol.liveness.LivenessAction.DEAD;
import static io.jburrow.protocol.liveness.LivenessAction.NONE;
import static io.jburrow.protocol.liveness.LivenessAction.SEND_PING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LivenessTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long PING = 15 * SECOND;
    private static final long IDLE = 45 * SECOND;
    private static final long WRAP_START = Long.MAX_VALUE - 10 * SECOND;

    @ParameterizedTest
    @ValueSource(longs = {0L, 123_456_789L, WRAP_START, Long.MIN_VALUE})
    void quietPeerIsPingedOnceThenDeclaredDead(long start) {
        Liveness liveness = new Liveness(PING, IDLE, start);

        assertThat(liveness.check(start)).isEqualTo(NONE);
        assertThat(liveness.check(start + PING - 1)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(start)).isEqualTo(start + PING);

        assertThat(liveness.check(start + PING)).isEqualTo(SEND_PING);
        liveness.onPingSent(start + PING, 1);

        assertThat(liveness.check(start + PING)).isEqualTo(NONE);
        assertThat(liveness.check(start + IDLE - 1)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(start + PING)).isEqualTo(start + IDLE);

        assertThat(liveness.check(start + IDLE)).isEqualTo(DEAD);
        assertThat(liveness.check(start + IDLE + 100 * SECOND)).isEqualTo(DEAD);
        assertThat(liveness.nextDeadlineNanos(start + IDLE + SECOND)).isEqualTo(start + IDLE + SECOND);
    }

    @Test
    void wrapStartActuallyCrossesTheWrap() {
        assertThat(WRAP_START + PING).isNegative();
    }

    @Test
    void busyPeerIsNeverPinged() {
        Liveness liveness = new Liveness(PING, IDLE, 0);

        for (long now = 0; now < 10 * IDLE; now += PING / 2) {
            liveness.onFrameReceived(now);
            assertThat(liveness.check(now + PING / 2 - 1)).isEqualTo(NONE);
            assertThat(liveness.nextDeadlineNanos(now)).isEqualTo(now + PING);
        }
    }

    @Test
    void pongClearsTheOutstandingPing() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.onPingSent(PING, 7);

        liveness.onFrameReceived(PING + SECOND);
        assertThat(liveness.onPongReceived(7)).isTrue();

        assertThat(liveness.check(2 * PING)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(PING + SECOND)).isEqualTo(2 * PING + SECOND);
        assertThat(liveness.check(2 * PING + SECOND)).isEqualTo(SEND_PING);
    }

    @Test
    void aSecondCopyOfTheMatchingPongIsAStray() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.onPingSent(PING, 7);

        assertThat(liveness.onPongReceived(7)).isTrue();
        assertThat(liveness.onPongReceived(7)).isFalse();
    }

    @Test
    void strayPongChangesNothingElse() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        assertThat(liveness.onPongReceived(7)).isFalse();

        liveness.onPingSent(PING, 7);
        assertThat(liveness.onPongReceived(8)).isFalse();

        assertThat(liveness.check(PING + SECOND)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(PING + SECOND)).isEqualTo(IDLE);
        assertThat(liveness.check(IDLE)).isEqualTo(DEAD);
        assertThat(liveness.onPongReceived(7)).isTrue();
    }

    @Test
    void pongStillMatchesWhenTheFrameWasCountedFirst() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.onPingSent(PING, 42);

        liveness.onFrameReceived(PING + 1);

        assertThat(liveness.onPongReceived(42)).isTrue();
    }

    @Test
    void pausedTimeNeverMakesThePeerDead() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.pause();

        for (long now = 0; now <= 3 * IDLE; now += SECOND) {
            assertThat(liveness.check(now)).isNotEqualTo(DEAD);
            if (liveness.check(now) == SEND_PING) {
                liveness.onPingSent(now, now);
            }
        }

        long resumedAt = 3 * IDLE;
        liveness.resume(resumedAt);
        assertThat(liveness.check(resumedAt)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(resumedAt)).isEqualTo(resumedAt + PING);
        assertThat(liveness.check(resumedAt + PING)).isEqualTo(SEND_PING);
        assertThat(liveness.check(resumedAt + IDLE)).isEqualTo(DEAD);
    }

    /** The peer, still reading, must keep hearing from a side that has stopped reading it. */
    @Test
    void aPausedSidePingsAtOnceThenEveryPingInterval() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        long pausedAt = 2 * SECOND;
        liveness.pause();

        assertThat(liveness.nextDeadlineNanos(pausedAt)).isEqualTo(pausedAt);
        assertThat(liveness.check(pausedAt)).isEqualTo(SEND_PING);
        liveness.onPingSent(pausedAt, 1);

        assertThat(liveness.check(pausedAt + PING - 1)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(pausedAt + 1)).isEqualTo(pausedAt + PING);
        assertThat(liveness.check(pausedAt + PING)).isEqualTo(SEND_PING);
        liveness.onPingSent(pausedAt + PING, 2);
        assertThat(liveness.nextDeadlineNanos(pausedAt + PING)).isEqualTo(pausedAt + 2 * PING);
    }

    /** Pausing again soon after a {@code PING} doesn't bring the next one forward, which a rate limit would refuse. */
    @Test
    void pingsStayAPingIntervalApartAcrossPauses() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.onPingSent(PING, 1);

        liveness.pause();
        assertThat(liveness.check(PING + SECOND)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(PING + SECOND)).isEqualTo(2 * PING);

        liveness.resume(PING + SECOND);
        liveness.pause();
        assertThat(liveness.check(2 * PING - 1)).isEqualTo(NONE);
        assertThat(liveness.check(2 * PING)).isEqualTo(SEND_PING);
    }

    @Test
    void resumeClearsAPingSentBeforeThePause() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.onPingSent(PING, 1);
        liveness.pause();

        liveness.resume(IDLE);

        assertThat(liveness.check(IDLE + PING)).isEqualTo(SEND_PING);
    }

    @Test
    void pauseIsAStateNotACounter() {
        Liveness liveness = new Liveness(PING, IDLE, 0);
        liveness.pause();
        liveness.pause();

        liveness.resume(IDLE);

        assertThat(liveness.check(IDLE)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(IDLE)).isEqualTo(IDLE + PING);
    }

    @Test
    void resumeWhenNotPausedDoesNothing() {
        Liveness liveness = new Liveness(PING, IDLE, 0);

        liveness.resume(IDLE - 1);

        assertThat(liveness.check(IDLE)).isEqualTo(DEAD);
    }

    @Test
    void reconfigureAppliesTheNewIntervalsFromNow() {
        long handshake = 10 * SECOND;
        Liveness liveness = new Liveness(handshake, handshake, 0);
        assertThat(liveness.nextDeadlineNanos(0)).isEqualTo(handshake);

        long helloAt = 4 * SECOND;
        liveness.reconfigure(PING, IDLE, helloAt);

        assertThat(liveness.check(handshake)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(helloAt)).isEqualTo(helloAt + PING);
        assertThat(liveness.check(helloAt + PING)).isEqualTo(SEND_PING);
        liveness.onPingSent(helloAt + PING, 1);
        assertThat(liveness.nextDeadlineNanos(helloAt + PING)).isEqualTo(helloAt + IDLE);
        assertThat(liveness.check(helloAt + IDLE)).isEqualTo(DEAD);
    }

    @Test
    void equalIntervalsNeverPing() {
        Liveness liveness = new Liveness(IDLE, IDLE, 0);

        assertThat(liveness.check(IDLE - 1)).isEqualTo(NONE);
        assertThat(liveness.nextDeadlineNanos(0)).isEqualTo(IDLE);
        assertThat(liveness.check(IDLE)).isEqualTo(DEAD);
    }

    @Test
    void nextDeadlineIsNowWhenAPingIsOverdue() {
        Liveness liveness = new Liveness(PING, IDLE, 0);

        assertThat(liveness.nextDeadlineNanos(PING + SECOND)).isEqualTo(PING + SECOND);
        assertThat(liveness.check(PING + SECOND)).isEqualTo(SEND_PING);
    }

    @Test
    void reportingAPingThatWasNotDueIsABug() {
        Liveness early = new Liveness(PING, IDLE, 0);
        assertThatIllegalStateException().isThrownBy(() -> early.onPingSent(PING - 1, 1));

        Liveness outstanding = new Liveness(PING, IDLE, 0);
        outstanding.onPingSent(PING, 1);
        assertThatIllegalStateException().isThrownBy(() -> outstanding.onPingSent(PING + 1, 2));

        Liveness paused = new Liveness(PING, IDLE, 0);
        paused.pause();
        paused.onPingSent(SECOND, 1);
        assertThatIllegalStateException().isThrownBy(() -> paused.onPingSent(SECOND + PING - 1, 2));
    }

    @Test
    void rejectsIntervalsThatCanNotWork() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Liveness(0, IDLE, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Liveness(PING, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Liveness(IDLE + 1, IDLE, 0));

        Liveness liveness = new Liveness(PING, IDLE, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> liveness.reconfigure(IDLE, PING, 0));
    }
}
