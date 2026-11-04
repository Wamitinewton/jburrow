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

    private static final Duration HOLD = Duration.ofSeconds(120);
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    private final MutableClock clock = new MutableClock();

    /** Without holds, as phase 1 and 2 ran: a released port is free at once. */
    private PortPool<String> pool(PortRange range) {
        return new PortPool<>(range, REUSE_DELAY, QUARANTINE, Duration.ZERO, new Random(42), clock);
    }

    private PortPool<String> heldPool(PortRange range) {
        return new PortPool<>(range, REUSE_DELAY, QUARANTINE, HOLD, new Random(42), clock);
    }

    @Test
    void startsWithEveryPortFree() {
        assertThat(pool(TEN_PORTS).snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0, 0));
    }

    @Test
    void reservesARequestedPortInRange() {
        PortPool<String> pool = pool(TEN_PORTS);

        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();

        assertThat(held.port()).isEqualTo(21_003);
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 1, 0, 0));
    }

    @Test
    void refusesARequestedPortOutOfRange() {
        PortPool<String> pool = pool(TEN_PORTS);

        assertThat(pool.reserveRequested(20_999, ALICE)).isEmpty();
        assertThat(pool.reserveRequested(21_010, ALICE)).isEmpty();
        assertThat(pool.snapshot().reserved()).isZero();
    }

    @Test
    void refusesARequestedPortAlreadyTaken() {
        PortPool<String> pool = pool(TEN_PORTS);
        pool.reserveRequested(21_003, ALICE).orElseThrow();

        assertThat(pool.reserveRequested(21_003, ALICE)).isEmpty();
    }

    @Test
    void grantsARequestedPortRightAfterItsRelease() {
        PortPool<String> pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(held.port(), held.holder());

        assertThat(pool.reserveRequested(21_003, ALICE))
                .map(PortPool.Held::port)
                .contains(21_003);
    }

    @Test
    void everyReservationHasANewHolder() {
        PortPool<String> pool = pool(TEN_PORTS);
        PortPool.Held first = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(first.port(), first.holder());

        PortPool.Held second = pool.reserveRequested(21_003, ALICE).orElseThrow();

        assertThat(second.holder()).isGreaterThan(first.holder());
    }

    @Test
    void reserveAnyAvoidsRecentlyReleasedPortsWhileCooledOnesExist() {
        PortPool<String> pool = pool(TEN_PORTS);
        List<PortPool.Held> all = reserveAll(pool);
        all.subList(0, 5).forEach(held -> pool.release(held.port(), held.holder()));
        clock.advance(REUSE_DELAY);
        all.subList(5, 10).forEach(held -> pool.release(held.port(), held.holder()));

        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            picked.add(pool.reserveAny(ALICE).orElseThrow().port());
        }

        assertThat(picked).containsExactlyInAnyOrder(21_000, 21_001, 21_002, 21_003, 21_004);
    }

    @Test
    void reserveAnyFallsBackToRecentlyReleasedPortsWhenNoCooledOneIsLeft() {
        PortPool<String> pool = pool(TEN_PORTS);
        List<PortPool.Held> all = reserveAll(pool);
        PortPool.Held last = all.get(9);
        pool.release(last.port(), last.holder());

        assertThat(pool.reserveAny(ALICE)).map(PortPool.Held::port).contains(last.port());
    }

    @Test
    void aReleasedPortCoolsAfterTheReuseDelay() {
        PortPool<String> pool = pool(new PortRange(21_000, 21_001));
        PortPool.Held first = pool.reserveRequested(21_000, ALICE).orElseThrow();
        pool.release(first.port(), first.holder());
        clock.advance(REUSE_DELAY.minusMillis(1));
        PortPool.Held other = pool.reserveRequested(21_001, ALICE).orElseThrow();
        pool.release(other.port(), other.holder());
        clock.advance(Duration.ofMillis(1));

        assertThat(pool.reserveAny(ALICE)).map(PortPool.Held::port).contains(21_000);
    }

    @Test
    void aQuarantinedPortIsUnavailableUntilTheQuarantineEnds() {
        PortPool<String> pool = pool(new PortRange(21_000, 21_000));
        PortPool.Held held = pool.reserveRequested(21_000, ALICE).orElseThrow();

        pool.quarantine(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(0, 0, 0, 1));
        assertThat(pool.reserveRequested(21_000, ALICE)).isEmpty();
        assertThat(pool.reserveAny(ALICE)).isEmpty();

        clock.advance(QUARANTINE.minusMillis(1));
        assertThat(pool.reserveAny(ALICE)).isEmpty();

        clock.advance(Duration.ofMillis(1));
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(1, 0, 0, 0));
        assertThat(pool.reserveRequested(21_000, ALICE)).isPresent();
    }

    @Test
    void aPortLeavingQuarantineCountsAsJustReleased() {
        PortPool<String> pool = pool(new PortRange(21_000, 21_001));
        PortPool.Held quarantined = pool.reserveRequested(21_000, ALICE).orElseThrow();
        pool.quarantine(quarantined.port(), quarantined.holder());
        clock.advance(QUARANTINE);

        assertThat(pool.reserveAny(ALICE)).map(PortPool.Held::port).contains(21_001);
    }

    @Test
    void aStaleHoldersReleaseIsIgnored() {
        PortPool<String> pool = pool(TEN_PORTS);
        PortPool.Held stale = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(stale.port(), stale.holder());
        PortPool.Held current = pool.reserveRequested(21_003, ALICE).orElseThrow();

        pool.release(stale.port(), stale.holder());
        pool.quarantine(stale.port(), stale.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 1, 0, 0));
        assertThat(pool.reserveRequested(21_003, ALICE)).isEmpty();

        pool.release(current.port(), current.holder());
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0, 0));
    }

    @Test
    void aDuplicateReleaseIsIgnored() {
        PortPool<String> pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();

        pool.release(held.port(), held.holder());
        pool.release(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0, 0));
    }

    @Test
    void aReleaseAfterQuarantineIsIgnored() {
        PortPool<String> pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();

        pool.quarantine(held.port(), held.holder());
        pool.release(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 0, 0, 1));
    }

    @Test
    void releasingAPortOutsideTheRangeIsABug() {
        PortPool<String> pool = pool(TEN_PORTS);

        assertThatIllegalArgumentException().isThrownBy(() -> pool.release(20_000, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> pool.quarantine(20_000, 1));
    }

    @Test
    void isExhaustedWhenEveryPortIsReserved() {
        PortPool<String> pool = pool(TEN_PORTS);
        reserveAll(pool);

        assertThat(pool.reserveAny(ALICE)).isEmpty();
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(0, 10, 0, 0));
    }

    @Test
    void reserveAnyReachesEveryPort() {
        PortPool<String> pool = pool(TEN_PORTS);
        Set<Integer> picked = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            PortPool.Held held = pool.reserveAny(ALICE).orElseThrow();
            picked.add(held.port());
            pool.release(held.port(), held.holder());
            clock.advance(REUSE_DELAY);
        }

        assertThat(picked).hasSize(10);
    }

    @Test
    void rejectsNegativeDurations() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new PortPool<>(TEN_PORTS, Duration.ofSeconds(-1), QUARANTINE, HOLD, new Random(), clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new PortPool<>(TEN_PORTS, REUSE_DELAY, Duration.ofSeconds(-1), HOLD, new Random(), clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PortPool<>(
                        TEN_PORTS, REUSE_DELAY, QUARANTINE, Duration.ofSeconds(-1), new Random(), clock));
    }

    @Test
    void anActivePortIsRefusedToItsOwnerAndToEveryoneElseAndSkippedByRandomDraws() {
        PortPool<String> pool = heldPool(new PortRange(21_000, 21_000));
        pool.reserveRequested(21_000, ALICE).orElseThrow();

        assertThat(pool.reserveRequested(21_000, ALICE)).isEmpty();
        assertThat(pool.reserveRequested(21_000, BOB)).isEmpty();
        assertThat(pool.reserveAny(BOB)).isEmpty();
    }

    @Test
    void aReleasedPortIsHeldForItsPrincipal() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();

        pool.release(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 0, 1, 0));
        assertThat(pool.reserveRequested(21_003, BOB)).isEmpty();
        assertThat(pool.reserveRequested(21_003, ALICE))
                .map(PortPool.Held::port)
                .contains(21_003);
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(9, 1, 0, 0));
    }

    @Test
    void aHeldPortIsNeverDrawnAtRandom() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        List<PortPool.Held> all = reserveAll(pool);
        PortPool.Held last = all.get(9);
        pool.release(last.port(), last.holder());

        assertThat(pool.reserveAny(ALICE)).isEmpty();
        assertThat(pool.reserveAny(BOB)).isEmpty();
    }

    @Test
    void aHoldEndsAfterTheWindowAndThePortIsAnyonesAlreadyCooled() {
        PortPool<String> pool = heldPool(new PortRange(21_000, 21_001));
        PortPool.Held held = pool.reserveRequested(21_000, ALICE).orElseThrow();
        pool.release(held.port(), held.holder());

        clock.advance(HOLD.minusMillis(1));
        assertThat(pool.reserveRequested(21_000, BOB)).isEmpty();

        clock.advance(Duration.ofMillis(1));
        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(2, 0, 0, 0));
        Set<Integer> drawn = new HashSet<>();
        drawn.add(pool.reserveAny(BOB).orElseThrow().port());
        drawn.add(pool.reserveAny(BOB).orElseThrow().port());
        assertThat(drawn).containsExactlyInAnyOrder(21_000, 21_001);
    }

    @Test
    void anExpiredHoldIsGrantedToAnyoneWhoAsks() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(held.port(), held.holder());
        clock.advance(HOLD);

        assertThat(pool.reserveRequested(21_003, BOB)).map(PortPool.Held::port).contains(21_003);
    }

    @Test
    void anExpiredHoldCountsAsReleasedWhenTheHoldBegan() {
        PortPool<String> pool = new PortPool<>(
                new PortRange(21_000, 21_001), REUSE_DELAY, QUARANTINE, Duration.ofSeconds(30), new Random(42), clock);
        PortPool.Held held = pool.reserveRequested(21_000, ALICE).orElseThrow();
        pool.release(held.port(), held.holder());
        clock.advance(Duration.ofSeconds(30));
        PortPool.Held other = pool.reserveRequested(21_001, BOB).orElseThrow();
        pool.release(other.port(), other.holder());
        clock.advance(Duration.ofSeconds(30));

        assertThat(pool.reserveAny(BOB)).map(PortPool.Held::port).contains(21_000);
    }

    @Test
    void aZeroWindowFreesAReleasedPortAtOnce() {
        PortPool<String> pool = pool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(held.port(), held.holder());

        assertThat(pool.snapshot()).isEqualTo(new PortPool.Snapshot(10, 0, 0, 0));
        assertThat(pool.reserveRequested(21_003, BOB)).isPresent();
    }

    @Test
    void aQuarantinedPortIsHeldForNobody() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();

        pool.quarantine(held.port(), held.holder());

        assertThat(pool.claims()).isEmpty();
        assertThat(pool.reserveRequested(21_003, ALICE)).isEmpty();
    }

    @Test
    void aHoldReclaimedAndReleasedAgainRestartsTheWindow() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        PortPool.Held first = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(first.port(), first.holder());
        clock.advance(Duration.ofSeconds(100));
        PortPool.Held second = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.release(second.port(), second.holder());

        clock.advance(Duration.ofSeconds(100));

        assertThat(pool.reserveRequested(21_003, BOB)).isEmpty();
        assertThat(pool.snapshot().held()).isOne();
    }

    @Test
    void claimsListReservedAndHeldPortsWithTheirPrincipals() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        pool.reserveRequested(21_001, ALICE).orElseThrow();
        PortPool.Held released = pool.reserveRequested(21_005, BOB).orElseThrow();
        pool.release(released.port(), released.holder());
        long holdEnd = clock.millis() + HOLD.toMillis();

        assertThat(pool.claims())
                .containsExactly(
                        new PortPool.Claim<>(21_001, ALICE, PortPool.Claim.ACTIVE),
                        new PortPool.Claim<>(21_005, BOB, holdEnd));

        clock.advance(HOLD);
        assertThat(pool.claims()).containsExactly(new PortPool.Claim<>(21_001, ALICE, PortPool.Claim.ACTIVE));
    }

    @Test
    void aSeededHoldKeepsAFreePortForItsPrincipalUntilItEnds() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        long until = clock.millis() + 45_000;

        assertThat(pool.seedHold(21_003, ALICE, until)).isTrue();

        assertThat(pool.reserveRequested(21_003, BOB)).isEmpty();
        assertThat(pool.claims()).containsExactly(new PortPool.Claim<>(21_003, ALICE, until));
        clock.advance(Duration.ofSeconds(45));
        assertThat(pool.reserveRequested(21_003, BOB)).isPresent();
    }

    @Test
    void aSeededHoldIsReclaimedByItsPrincipal() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        pool.seedHold(21_003, ALICE, clock.millis() + 45_000);

        assertThat(pool.reserveRequested(21_003, ALICE))
                .map(PortPool.Held::port)
                .contains(21_003);
    }

    @Test
    void aSeededHoldNeverReplacesAnything() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        long until = clock.millis() + 45_000;
        pool.reserveRequested(21_001, ALICE).orElseThrow();
        PortPool.Held released = pool.reserveRequested(21_002, ALICE).orElseThrow();
        pool.release(released.port(), released.holder());
        PortPool.Held quarantined = pool.reserveRequested(21_003, ALICE).orElseThrow();
        pool.quarantine(quarantined.port(), quarantined.holder());

        assertThat(pool.seedHold(21_001, BOB, until)).isFalse();
        assertThat(pool.seedHold(21_002, BOB, until)).isFalse();
        assertThat(pool.seedHold(21_003, BOB, until)).isFalse();
        assertThat(pool.reserveRequested(21_002, ALICE)).isPresent();
    }

    @Test
    void aSeededHoldOutsideTheRangeOrAlreadyOverIsDropped() {
        PortPool<String> pool = heldPool(TEN_PORTS);

        assertThat(pool.seedHold(20_999, ALICE, clock.millis() + 45_000)).isFalse();
        assertThat(pool.seedHold(21_003, ALICE, clock.millis())).isFalse();
        assertThat(pool.claims()).isEmpty();
    }

    @Test
    void seededHoldsEndInTheOrderOfTheirEndsNotOfTheirSeeding() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        pool.seedHold(21_001, ALICE, clock.millis() + 100_000);
        pool.seedHold(21_002, ALICE, clock.millis() + 10_000);

        clock.advance(Duration.ofSeconds(10));

        assertThat(pool.reserveRequested(21_002, BOB)).isPresent();
        assertThat(pool.reserveRequested(21_001, BOB)).isEmpty();
    }

    @Test
    void theVersionMovesOnEveryChangeAndOnlyThen() {
        PortPool<String> pool = heldPool(TEN_PORTS);
        long start = pool.version();
        assertThat(pool.version()).isEqualTo(start);

        PortPool.Held held = pool.reserveRequested(21_003, ALICE).orElseThrow();
        long reserved = pool.version();
        assertThat(reserved).isNotEqualTo(start);

        pool.release(held.port(), held.holder());
        long released = pool.version();
        assertThat(released).isNotEqualTo(reserved);

        clock.advance(HOLD);
        long expired = pool.version();
        assertThat(expired).isNotEqualTo(released);

        pool.seedHold(21_004, BOB, clock.millis() + 1_000);
        long seeded = pool.version();
        assertThat(seeded).isNotEqualTo(expired);

        PortPool.Held quarantined = pool.reserveAny(ALICE).orElseThrow();
        long drawn = pool.version();
        pool.quarantine(quarantined.port(), quarantined.holder());
        assertThat(pool.version()).isNotEqualTo(drawn);

        long settled = pool.version();
        pool.reserveRequested(21_004, ALICE);
        pool.release(held.port(), held.holder());
        pool.seedHold(20_000, ALICE, clock.millis() + 1_000);
        assertThat(pool.version()).isEqualTo(settled);
    }

    /**
     * A scan of the range per reservation would take billions of steps here. The bound is loose on purpose: it
     * catches a change in complexity, not a slow machine.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void reservationCostDoesNotGrowWithTheRange() {
        PortPool<String> pool = pool(PortRange.parse("1024-65535"));
        List<PortPool.Held> held = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            held.add(pool.reserveAny(ALICE).orElseThrow());
        }

        for (int i = 0; i < 100_000; i++) {
            PortPool.Held released =
                    held.set(i % held.size(), pool.reserveAny(ALICE).orElseThrow());
            pool.release(released.port(), released.holder());
            clock.advance(Duration.ofMillis(1));
        }

        assertThat(pool.snapshot().reserved()).isEqualTo(60_000);
    }

    private static List<PortPool.Held> reserveAll(PortPool<String> pool) {
        List<PortPool.Held> held = new ArrayList<>();
        for (int port = TEN_PORTS.first(); port <= TEN_PORTS.last(); port++) {
            held.add(pool.reserveRequested(port, ALICE).orElseThrow());
        }
        return held;
    }
}
