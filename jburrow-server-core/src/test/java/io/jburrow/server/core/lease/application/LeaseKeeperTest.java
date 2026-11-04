package io.jburrow.server.core.lease.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.lease.application.port.LeaseEvents;
import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.domain.Lease;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.MutableClock;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaseKeeperTest {

    private static final Duration WINDOW = Duration.ofSeconds(120);
    private static final Duration REFRESH = Duration.ofSeconds(30);
    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EARLIER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EVEN_EARLIER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /** Principals that start with {@code !} don't parse, as a malformed row wouldn't. */
    private static final LeaseKeeper.Principals<String> PRINCIPALS = new LeaseKeeper.Principals<>(
            principal -> principal, text -> text.startsWith("!") ? Optional.empty() : Optional.of(text));

    private final MutableClock clock = new MutableClock();
    private final SubdomainRoutes<String, String> routes =
            new SubdomainRoutes<>(Blocklist.defaults(Set.of()), new NameGenerator(new Random(7)), WINDOW, clock);
    private final PortReservationService<String> ports = new PortReservationService<>(new PortPool<>(
            new PortRange(20_000, 20_009),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            WINDOW,
            new Random(7),
            clock));
    private final InMemoryLeaseStore store = new InMemoryLeaseStore();
    private final RecordingEvents events = new RecordingEvents();
    private final LeaseKeeper<String> keeper = new LeaseKeeper<>(
            routes, ports, store, events, new LeaseKeeper.Settings(INSTANCE, WINDOW, REFRESH), PRINCIPALS, clock);

    @Test
    void anIdleServerWritesNothingAndQueriesNothing() {
        for (int second = 0; second < 300; second++) {
            keeper.tick();
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(store.queries).isZero();
    }

    @Test
    void aClaimIsWrittenOnTheNextTickAndNotAgainUntilTheRefresh() {
        SubdomainName name = routes.claimRandom("alice", "tunnel-1");

        keeper.tick();

        assertThat(store.replaces).isEqualTo(1);
        assertThat(store.of(INSTANCE))
                .containsExactly(Lease.name(
                        name.value(),
                        "alice",
                        true,
                        clock.instant().plus(REFRESH).plus(WINDOW)));

        for (int second = 1; second < 30; second++) {
            clock.advance(Duration.ofSeconds(1));
            keeper.tick();
        }
        assertThat(store.replaces).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        keeper.tick();
        assertThat(store.replaces).isEqualTo(2);
        assertThat(store.of(INSTANCE).getFirst().expiresAt())
                .isEqualTo(clock.instant().plus(REFRESH).plus(WINDOW));
    }

    @Test
    void anActivePortCountsForARefreshAndAWindowAndAHeldOneUntilItsHoldEnds() {
        Reservation reservation = ports.reserve(OptionalInt.of(20_003), "alice");
        keeper.tick();
        Instant writtenAt = clock.instant();
        assertThat(store.of(INSTANCE))
                .containsExactly(Lease.port(
                        20_003, "alice", true, writtenAt.plus(REFRESH).plus(WINDOW)));

        clock.advance(Duration.ofSeconds(5));
        ports.release(reservation);
        Instant releasedAt = clock.instant();
        keeper.tick();

        assertThat(store.of(INSTANCE)).containsExactly(Lease.port(20_003, "alice", false, releasedAt.plus(WINDOW)));
    }

    @Test
    void aHeldNameIsWrittenAsHeldUntilItsHoldEnds() {
        SubdomainName name = routes.claim(new SubdomainName("quiet-otter"), "alice", "tunnel-1");
        routes.release(name, "tunnel-1");
        Instant releasedAt = clock.instant();

        keeper.tick();

        assertThat(store.of(INSTANCE))
                .containsExactly(Lease.name("quiet-otter", "alice", false, releasedAt.plus(WINDOW)));
    }

    @Test
    void onceEverythingIsGoneOneEmptySnapshotIsWrittenAndThenNothing() {
        SubdomainName name = routes.claim(new SubdomainName("quiet-otter"), "alice", "tunnel-1");
        keeper.tick();
        routes.release(name, "tunnel-1");
        keeper.tick();
        assertThat(store.replaces).isEqualTo(2);

        clock.advance(WINDOW.plusSeconds(1));
        keeper.tick();
        assertThat(store.replaces).isEqualTo(3);
        assertThat(store.of(INSTANCE)).isEmpty();

        int queries = store.queries;
        for (int second = 0; second < 300; second++) {
            clock.advance(Duration.ofSeconds(1));
            keeper.tick();
        }
        assertThat(store.queries).isEqualTo(queries);
    }

    @Test
    void aFailedWriteIsReportedAndRetriedNextTickWithAFreshSnapshot() {
        SubdomainName first = routes.claim(new SubdomainName("quiet-otter"), "alice", "tunnel-1");
        store.failure = new IllegalStateException("Postgres is down");

        keeper.tick();
        clock.advance(Duration.ofSeconds(1));
        keeper.tick();

        assertThat(events.failures).containsExactly(1, 2);
        assertThat(store.replaces).isZero();

        SubdomainName second = routes.claim(new SubdomainName("brave-heron"), "bob", "tunnel-2");
        store.failure = null;
        clock.advance(Duration.ofSeconds(1));
        keeper.tick();

        assertThat(store.replaces).isEqualTo(1);
        assertThat(store.of(INSTANCE)).extracting(Lease::key).containsExactlyInAnyOrder(first.value(), second.value());
        assertThat(events.written).containsExactly(2);

        clock.advance(Duration.ofSeconds(1));
        keeper.tick();
        assertThat(store.replaces).isEqualTo(1);
    }

    @Test
    void aFailingStoreNeverThrowsOutOfATick() {
        routes.claimRandom("alice", "tunnel-1");
        store.failure = new IllegalStateException("Postgres is down");

        keeper.tick();

        assertThat(events.failures).containsExactly(1);
    }

    @Test
    void flushWritesAtOnceEvenWhenNothingChanged() {
        routes.claimRandom("alice", "tunnel-1");
        keeper.tick();

        assertThat(keeper.flush()).isTrue();

        assertThat(store.replaces).isEqualTo(2);
    }

    @Test
    void flushOfAnIdleServerWritesNothingAndReportsAFailedWrite() {
        assertThat(keeper.flush()).isTrue();
        assertThat(store.queries).isZero();

        routes.claimRandom("alice", "tunnel-1");
        store.failure = new IllegalStateException("Postgres is down");
        assertThat(keeper.flush()).isFalse();
    }

    @Test
    void seedHoldsTheLatestRowOfEachKeyAndDropsWhatCantBeHeld() {
        Instant now = clock.instant();
        store.put(
                EVEN_EARLIER,
                List.of(
                        Lease.name("quiet-otter", "alice", true, now.plusSeconds(60)),
                        Lease.port(20_001, "alice", false, now.plusSeconds(90))));
        store.put(
                EARLIER,
                List.of(
                        Lease.name("quiet-otter", "alice", false, now.plusSeconds(100)),
                        Lease.port(20_002, "bob", true, now.plusSeconds(150)),
                        Lease.port(30_000, "bob", true, now.plusSeconds(150)),
                        Lease.name("expired-heron", "bob", false, now.minusSeconds(1)),
                        Lease.name("bad--name", "bob", false, now.plusSeconds(10)),
                        Lease.name("odd-principal", "!", false, now.plusSeconds(10))));

        LeaseKeeper.Seeded seeded = keeper.seed();

        assertThat(seeded.names()).isEqualTo(1);
        assertThat(seeded.ports()).isEqualTo(2);
        assertThat(seeded.dropped()).isEqualTo(3);
        assertThat(seeded.oldestExpiry()).contains(now.plusSeconds(90));
        assertThat(seeded.newestExpiry()).contains(now.plusSeconds(150));
        assertThat(routes.snapshot(now)).singleElement().satisfies(claim -> {
            assertThat(claim.name().value()).isEqualTo("quiet-otter");
            assertThat(claim.principal()).isEqualTo("alice");
            assertThat(claim.heldUntil()).isEqualTo(now.plusSeconds(100));
        });
        assertThat(ports.snapshot(now))
                .extracting(claim -> claim.port() + " " + claim.principal() + " " + claim.heldUntil())
                .containsExactlyInAnyOrder("20001 alice " + now.plusSeconds(90), "20002 bob " + now.plusSeconds(150));
    }

    @Test
    void aSeededHoldYieldsToItsPrincipalOnly() {
        Instant now = clock.instant();
        store.put(EARLIER, List.of(Lease.port(20_004, "alice", true, now.plusSeconds(150))));
        keeper.seed();

        assertThat(ports.reserve(OptionalInt.of(20_004), "alice").port()).isEqualTo(20_004);
    }

    @Test
    void seedPurgesRowsExpiredForMoreThanADay() {
        Instant now = clock.instant();
        store.put(
                EARLIER,
                List.of(
                        Lease.name(
                                "long-gone",
                                "alice",
                                false,
                                now.minus(Duration.ofDays(1)).minusSeconds(1)),
                        Lease.name("just-gone", "alice", false, now.minusSeconds(1))));

        LeaseKeeper.Seeded seeded = keeper.seed();

        assertThat(seeded.purged()).isEqualTo(1);
        assertThat(store.of(EARLIER)).extracting(Lease::key).containsExactly("just-gone");
    }

    @Test
    void theSeededHoldsAreWrittenUnderThisInstanceOnTheFirstTick() {
        Instant now = clock.instant();
        store.put(EARLIER, List.of(Lease.name("quiet-otter", "alice", false, now.plusSeconds(100))));
        keeper.seed();

        keeper.tick();

        assertThat(store.of(INSTANCE)).containsExactly(Lease.name("quiet-otter", "alice", false, now.plusSeconds(100)));
    }

    @Test
    void aRowTheDomainRefusesIsDroppedNotFatal() {
        Instant now = clock.instant();
        store.putRecords(
                EARLIER,
                List.of(
                        new LeaseRecord(LeaseRecord.Kind.PORT, " ", "alice", false, now.plusSeconds(60)),
                        new LeaseRecord(LeaseRecord.Kind.NAME, "quiet-otter", "alice", false, now.plusSeconds(60))));

        LeaseKeeper.Seeded seeded = keeper.seed();

        assertThat(seeded.names()).isEqualTo(1);
        assertThat(seeded.dropped()).isEqualTo(1);
    }

    @Test
    void aFailingSeedThrows() {
        store.failure = new IllegalStateException("Postgres is down");

        assertThatThrownBy(keeper::seed).isInstanceOf(IllegalStateException.class);
    }

    private static final class RecordingEvents implements LeaseEvents {

        final List<Integer> written = new ArrayList<>();
        final List<Integer> failures = new ArrayList<>();

        @Override
        public void written(int leases, Duration took) {
            written.add(leases);
        }

        @Override
        public void writeFailed(RuntimeException failure, Duration took, int consecutive) {
            failures.add(consecutive);
        }
    }
}
