package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamQuotaTest {

    @Test
    void grantsUpToTheCapAndRefusesPastIt() {
        StreamQuota quota = new StreamQuota(3);

        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isFalse();
        assertThat(quota.inUse()).isEqualTo(3);
        assertThat(quota.max()).isEqualTo(3);
    }

    @Test
    void aReleaseMakesRoomAgain() {
        StreamQuota quota = new StreamQuota(1);
        quota.tryAcquire();

        quota.release();

        assertThat(quota.inUse()).isZero();
        assertThat(quota.tryAcquire()).isTrue();
    }

    @Test
    void releasingMoreThanWasAcquiredIsABug() {
        StreamQuota quota = new StreamQuota(1);
        quota.tryAcquire();
        quota.release();

        assertThatIllegalStateException().isThrownBy(quota::release);
        assertThat(quota.inUse()).isZero();
    }

    @Test
    void theCapIsPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StreamQuota(0));
    }

    @Test
    void neverExceedsTheCapUnderContentionAndEndsAtZero() throws Exception {
        int threads = 8;
        int pairs = 100_000;
        int max = 5;
        StreamQuota quota = new StreamQuota(max);
        AtomicInteger highest = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                workers.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < pairs; i++) {
                        while (!quota.tryAcquire()) {
                            Thread.onSpinWait();
                        }
                        highest.accumulateAndGet(quota.inUse(), Math::max);
                        quota.release();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(quota.inUse()).isZero();
        assertThat(highest.get()).isBetween(1, max);
    }
}
