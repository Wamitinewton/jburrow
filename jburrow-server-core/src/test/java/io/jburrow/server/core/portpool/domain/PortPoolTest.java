package io.jburrow.server.core.portpool.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PortPoolTest {

    private static final PortRange TEN_PORTS = new PortRange(21_000, 21_009);
    private static final Duration REUSE_DELAY = Duration.ofSeconds(60);
    private static final Duration QUARANTINE = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock();

    private PortPool pool(PortRange range) {
        return new PortPool(range, REUSE_DELAY, QUARANTINE, new Random(42), clock);
    }

    @Test
    void startsWithEveryPortFree() {
        assertThat(pool(TEN_PORTS).snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0));
    }

    @Test
    void reservesARequestedPortInRange() {
        PortPool pool = pool(TEN_PORTS);

        PortPool.Held held = pool.reserveRequested(21_003).orElseThrow();

        assertThat(held.port()).isEqualTo(21_003);
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 1, 0));
    }

    @Test
    void refusesARequestedPortOutOfRange() {
        PortPool pool = pool(TEN_PORTS);

        assertThat(pool.reserveRequested(20_999)).isEmpty();
        assertThat(pool.reserveRequested(21_010)).isEmpty();
        assertThat(pool.snapshot().reserved()).isZero();
    }

    @Test
    void refusesARequestedPortAlreadyTaken() {
        PortPool pool = pool(TEN_PORTS);
        pool.reserveRequested(21_003).orElseThrow();

        assertThat(pool.reserveRequested(21_003)).isEmpty();
    }

    @Test
    void grantsARequestedPortRightAfterItsRelease() {
        PortPool pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003).orElseThrow();
        pool.release(held.port(), held.holder());

        assertThat(pool.reserveRequested(21_003)).map(PortPool.Held::port).contains(21_003);
    }

    @Test
    void everyReservationHasANewHolder() {
        PortPool pool = pool(TEN_PORTS);
        PortPool.Held first = pool.reserveRequested(21_003).orElseThrow();
        pool.release(first.port(), first.holder());

        PortPool.Held second = pool.reserveRequested(21_003).orElseThrow();

        assertThat(second.holder()).isGreaterThan(first.holder());
    }

    @Test
    void reserveAnyAvoidsRecentlyReleasedPortsWhileCooledOnesExist() {
        PortPool pool = pool(TEN_PORTS);
        List<PortPool.Held> all = reserveAll(pool);
        all.subList(0, 5).forEach(held -> pool.release(held.port(), held.holder()));
        clock.advance(REUSE_DELAY);
        all.subList(5, 10).forEach(held -> pool.release(held.port(), held.holder()));

        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            picked.add(pool.reserveAny().orElseThrow().port());
        }

        assertThat(picked).containsExactlyInAnyOrder(21_000, 21_001, 21_002, 21_003, 21_004);
    }

    @Test
    void reserveAnyFallsBackToRecentlyReleasedPortsWhenNoCooledOneIsLeft() {
        PortPool pool = pool(TEN_PORTS);
        List<PortPool.Held> all = reserveAll(pool);
        PortPool.Held last = all.get(9);
        pool.release(last.port(), last.holder());

        assertThat(pool.reserveAny()).map(PortPool.Held::port).contains(last.port());
    }

    @Test
    void aReleasedPortCoolsAfterTheReuseDelay() {
        PortPool pool = pool(new PortRange(21_000, 21_001));
        PortPool.Held first = pool.reserveRequested(21_000).orElseThrow();
        pool.release(first.port(), first.holder());
        clock.advance(REUSE_DELAY.minusMillis(1));
        PortPool.Held other = pool.reserveRequested(21_001).orElseThrow();
        pool.release(other.port(), other.holder());
        clock.advance(Duration.ofMillis(1));

        assertThat(pool.reserveAny()).map(PortPool.Held::port).contains(21_000);
    }

    @Test
    void aQuarantinedPortIsUnavailableUntilTheQuarantineEnds() {
        PortPool pool = pool(new PortRange(21_000, 21_000));
        PortPool.Held held = pool.reserveRequested(21_000).orElseThrow();

        pool.quarantine(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(0, 0, 1));
        assertThat(pool.reserveRequested(21_000)).isEmpty();
        assertThat(pool.reserveAny()).isEmpty();

        clock.advance(QUARANTINE.minusMillis(1));
        assertThat(pool.reserveAny()).isEmpty();

        clock.advance(Duration.ofMillis(1));
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(1, 0, 0));
        assertThat(pool.reserveRequested(21_000)).isPresent();
    }

    @Test
    void aPortLeavingQuarantineCountsAsJustReleased() {
        PortPool pool = pool(new PortRange(21_000, 21_001));
        PortPool.Held quarantined = pool.reserveRequested(21_000).orElseThrow();
        pool.quarantine(quarantined.port(), quarantined.holder());
        clock.advance(QUARANTINE);

        assertThat(pool.reserveAny()).map(PortPool.Held::port).contains(21_001);
    }

    @Test
    void aStaleHoldersReleaseIsIgnored() {
        PortPool pool = pool(TEN_PORTS);
        PortPool.Held stale = pool.reserveRequested(21_003).orElseThrow();
        pool.release(stale.port(), stale.holder());
        PortPool.Held current = pool.reserveRequested(21_003).orElseThrow();

        pool.release(stale.port(), stale.holder());
        pool.quarantine(stale.port(), stale.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 1, 0));
        assertThat(pool.reserveRequested(21_003)).isEmpty();

        pool.release(current.port(), current.holder());
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0));
    }

    @Test
    void aDuplicateReleaseIsIgnored() {
        PortPool pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003).orElseThrow();

        pool.release(held.port(), held.holder());
        pool.release(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0));
    }

    @Test
    void aReleaseAfterQuarantineIsIgnored() {
        PortPool pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003).orElseThrow();

        pool.quarantine(held.port(), held.holder());
        pool.release(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 0, 1));
    }

    @Test
    void releasingAPortOutsideTheRangeIsABug() {
        PortPool pool = pool(TEN_PORTS);

        assertThatIllegalArgumentException().isThrownBy(() -> pool.release(20_000, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> pool.quarantine(20_000, 1));
    }

    @Test
    void isExhaustedWhenEveryPortIsReserved() {
        PortPool pool = pool(TEN_PORTS);
        reserveAll(pool);

        assertThat(pool.reserveAny()).isEmpty();
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(0, 10, 0));
    }

    @Test
    void reserveAnyReachesEveryPort() {
        PortPool pool = pool(TEN_PORTS);
        Set<Integer> picked = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            PortPool.Held held = pool.reserveAny().orElseThrow();
            picked.add(held.port());
            pool.release(held.port(), held.holder());
            clock.advance(REUSE_DELAY);
        }

        assertThat(picked).hasSize(10);
    }

    @Test
    void rejectsNegativeDurations() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PortPool(TEN_PORTS, Duration.ofSeconds(-1), QUARANTINE, new Random(), clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PortPool(TEN_PORTS, REUSE_DELAY, Duration.ofSeconds(-1), new Random(), clock));
    }

    /**
     * A scan of the range per reservation would take billions of steps here. The bound is loose on purpose: it
     * catches a change in complexity, not a slow machine.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void reservationCostDoesNotGrowWithTheRange() {
        PortPool pool = pool(PortRange.parse("1024-65535"));
        List<PortPool.Held> held = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            held.add(pool.reserveAny().orElseThrow());
        }

        for (int i = 0; i < 100_000; i++) {
            PortPool.Held released = held.set(i % held.size(), pool.reserveAny().orElseThrow());
            pool.release(released.port(), released.holder());
            clock.advance(Duration.ofMillis(1));
        }

        assertThat(pool.snapshot().reserved()).isEqualTo(60_000);
    }

    private static List<PortPool.Held> reserveAll(PortPool pool) {
        List<PortPool.Held> held = new ArrayList<>();
        for (int port = TEN_PORTS.first(); port <= TEN_PORTS.last(); port++) {
            held.add(pool.reserveRequested(port).orElseThrow());
        }
        return held;
    }
}
