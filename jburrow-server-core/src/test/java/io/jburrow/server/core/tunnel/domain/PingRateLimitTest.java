package io.jburrow.server.core.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PingRateLimitTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void allowsABurstOfFiveThenRefuses() {
        PingRateLimit limit = PingRateLimit.standard(0);

        for (int i = 0; i < 5; i++) {
            assertThat(limit.tryAcquire(0)).as("ping %d", i + 1).isTrue();
        }
        assertThat(limit.tryAcquire(0)).isFalse();
    }

    @Test
    void refillsOnePerSecond() {
        PingRateLimit limit = PingRateLimit.standard(0);
        drain(limit, 0);

        assertThat(limit.tryAcquire(SECOND - 1)).isFalse();
        assertThat(limit.tryAcquire(SECOND)).isTrue();
        assertThat(limit.tryAcquire(SECOND)).isFalse();
        assertThat(limit.tryAcquire(2 * SECOND)).isTrue();
    }

    @Test
    void sustainsOnePerSecondIndefinitely() {
        PingRateLimit limit = PingRateLimit.standard(0);

        for (int i = 0; i < 1_000; i++) {
            assertThat(limit.tryAcquire(i * SECOND)).isTrue();
        }
    }

    @Test
    void aLongIdlePeriodRefillsOnlyToTheBurst() {
        PingRateLimit limit = PingRateLimit.standard(0);
        drain(limit, 0);
        long later = TimeUnit.HOURS.toNanos(1);

        for (int i = 0; i < 5; i++) {
            assertThat(limit.tryAcquire(later)).isTrue();
        }
        assertThat(limit.tryAcquire(later)).isFalse();
    }

    @Test
    void holdsAcrossTheNanoTimeWrap() {
        long start = Long.MAX_VALUE - SECOND / 2;
        PingRateLimit limit = PingRateLimit.standard(start);
        drain(limit, start);

        assertThat(limit.tryAcquire(start + SECOND - 1)).isFalse();
        assertThat(limit.tryAcquire(start + SECOND)).isTrue();
    }

    @Test
    void rejectsANonPositiveBurstOrInterval() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PingRateLimit(0, SECOND, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PingRateLimit(5, 0, 0));
    }

    private static void drain(PingRateLimit limit, long nowNanos) {
        for (int i = 0; i < PingRateLimit.STANDARD_BURST; i++) {
            assertThat(limit.tryAcquire(nowNanos)).isTrue();
        }
    }
}
