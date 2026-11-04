package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Every row of ARCHITECTURE.md §Reconnecting, phase 4, with a seeded random source and a clock the test moves. */
class ReconnectScheduleTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    private final FakeClock clock = new FakeClock();
    private long seed = 7;
    private ReconnectSchedule schedule = schedule();

    @RepeatedTest(20)
    void afterALostConnectionTheFirstAttemptIsWithinHalfASecondThenTheBackoff() {
        schedule = schedule();
        schedule.afterLoss();

        assertThat(next()).isBetween(Duration.ZERO, Duration.ofMillis(500));
        assertBackoff(1, 2, 4, 8, 16, 30, 30, 30);
    }

    @RepeatedTest(20)
    void afterGoAwayTheServersDelayComesFirstThenAboutOneSecondForAMinute() {
        schedule = schedule();
        schedule.afterGoAway(Duration.ofMillis(830));

        assertThat(next()).isEqualTo(Duration.ofMillis(830));
        clock.advance(Duration.ofMillis(830));
        for (int attempt = 0; attempt < 50; attempt++) {
            Duration delay = next();
            assertThat(delay).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
            clock.advance(delay);
        }
        clock.advance(Duration.ofSeconds(30));
        assertBackoff(1, 2, 4);
    }

    @Test
    void theGoAwayMinuteCountsFromTheFirstAttempt() {
        schedule.afterGoAway(Duration.ofSeconds(30));
        assertThat(next()).isEqualTo(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(89));
        assertThat(next()).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
        clock.advance(SECOND);
        assertThat(next()).isBetween(Duration.ofMillis(500), SECOND);
    }

    @RepeatedTest(20)
    void retryAfterComesFirstAndIsAFloorForTheBackoff() {
        schedule = schedule();
        schedule.afterRetryAfter(Duration.ofSeconds(5));

        assertThat(next()).isEqualTo(Duration.ofSeconds(5));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(next()).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        }
        assertThat(next()).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(8));
    }

    @Test
    void aRetryAfterNeverShortensTheBackoffAndCountsForAtMostAMinute() {
        schedule.afterLoss();
        assertBackoffAfterFirst(1, 2, 4, 8);

        schedule.afterRetryAfter(Duration.ZERO);
        assertThat(next()).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(16));

        schedule.afterRetryAfter(Duration.ofHours(1));
        assertThat(next()).isEqualTo(Duration.ofSeconds(60));
        assertThat(next()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theBackoffCarriesOnAcrossShortSessions() {
        schedule.afterLoss();
        assertBackoffAfterFirst(1, 2);
        schedule.established();
        clock.advance(Duration.ofSeconds(59));
        schedule.ended();

        schedule.afterLoss();

        assertThat(next()).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void aSessionThatStayedAMinuteResetsTheBackoff() {
        schedule.afterLoss();
        assertBackoffAfterFirst(1, 2, 4, 8, 16);
        schedule.established();
        clock.advance(Duration.ofSeconds(60));
        schedule.ended();

        schedule.afterLoss();

        assertThat(next()).isBetween(Duration.ZERO, Duration.ofMillis(500));
        assertBackoff(1, 2);
    }

    @Test
    void theFirstSessionIsRetriedOnTheGoAwayCadenceForAMinuteThenGivenUp() {
        schedule.startingUp(Duration.ZERO);
        assertThat(next()).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));

        clock.advance(Duration.ofSeconds(30));
        schedule.startingUp(Duration.ofSeconds(5));
        assertThat(next()).isEqualTo(Duration.ofSeconds(5));

        clock.advance(Duration.ofSeconds(29));
        schedule.startingUp(Duration.ZERO);
        assertThat(next()).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));

        clock.advance(SECOND);
        schedule.startingUp(Duration.ZERO);
        assertThat(schedule.nextDelay()).isEmpty();
    }

    @Test
    void theSameSeedGivesTheSameDelays() {
        ReconnectSchedule one = new ReconnectSchedule(ReconnectSchedule.STANDARD_UNIT, new SplittableRandom(42), clock);
        ReconnectSchedule two = new ReconnectSchedule(ReconnectSchedule.STANDARD_UNIT, new SplittableRandom(42), clock);
        one.afterLoss();
        two.afterLoss();

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(one.nextDelay()).isEqualTo(two.nextDelay());
        }
    }

    @Test
    void aShorterUnitScalesTheScheduleButNotTheServersDelays() {
        schedule = new ReconnectSchedule(Duration.ofMillis(10), new SplittableRandom(seed), clock);

        schedule.afterLoss();
        assertThat(next()).isBetween(Duration.ZERO, Duration.ofMillis(5));
        assertThat(next()).isBetween(Duration.ofMillis(5), Duration.ofMillis(10));

        schedule.afterGoAway(Duration.ofMillis(830));
        assertThat(next()).isEqualTo(Duration.ofMillis(830));
        assertThat(next()).isBetween(Duration.ofMillis(8), Duration.ofMillis(12));
    }

    @Test
    void theUnitIsAtMostOneSecond() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconnectSchedule(Duration.ofSeconds(2), new SplittableRandom(), clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconnectSchedule(Duration.ZERO, new SplittableRandom(), clock));
    }

    /** The next delays are the backoff's, in seconds before jitter: each between half and all of it. */
    private void assertBackoff(int... seconds) {
        for (int base : seconds) {
            assertThat(next()).isBetween(Duration.ofMillis(base * 500L), Duration.ofSeconds(base));
        }
    }

    /** Takes the run's first delay, then {@link #assertBackoff}. */
    private void assertBackoffAfterFirst(int... seconds) {
        next();
        assertBackoff(seconds);
    }

    private Duration next() {
        Optional<Duration> delay = schedule.nextDelay();
        assertThat(delay).isPresent();
        return delay.get();
    }

    private ReconnectSchedule schedule() {
        return new ReconnectSchedule(ReconnectSchedule.STANDARD_UNIT, new SplittableRandom(seed++), clock);
    }

    private static final class FakeClock implements InstantSource {

        private Instant now = Instant.parse("2026-11-03T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
