package io.jburrow.server.core.lease.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeaseSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @Test
    void theLatestExpiryOfEachKeyWinsAndExpiredRowsAreLeftOut() {
        Lease earlier = Lease.name("quiet-otter", "alice", true, NOW.plusSeconds(30));
        Lease later = Lease.name("quiet-otter", "alice", false, NOW.plusSeconds(90));
        Lease samePortNumberAsAName = Lease.port(20_417, "bob", true, NOW.plusSeconds(10));
        Lease nameThatLooksLikeThePort = Lease.name("20417", "bob", false, NOW.plusSeconds(10));
        Lease expired = Lease.port(20_001, "bob", false, NOW);

        LeaseSnapshot snapshot = LeaseSnapshot.latestPerKey(
                List.of(earlier, samePortNumberAsAName, later, nameThatLooksLikeThePort, expired), NOW);

        assertThat(snapshot.leases()).containsExactlyInAnyOrder(later, samePortNumberAsAName, nameThatLooksLikeThePort);
        assertThat(snapshot.count(LeaseKind.NAME)).isEqualTo(2);
        assertThat(snapshot.count(LeaseKind.PORT)).isEqualTo(1);
        assertThat(snapshot.oldestExpiry()).contains(NOW.plusSeconds(10));
        assertThat(snapshot.newestExpiry()).contains(NOW.plusSeconds(90));
    }

    @Test
    void aSnapshotHoldsOneLeasePerKey() {
        assertThatThrownBy(() -> new LeaseSnapshot(
                        List.of(Lease.port(20_417, "alice", true, NOW), Lease.port(20_417, "bob", true, NOW))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptySnapshotHasNoExpiries() {
        assertThat(LeaseSnapshot.EMPTY.isEmpty()).isTrue();
        assertThat(LeaseSnapshot.EMPTY.oldestExpiry()).isEmpty();
    }

    @Test
    void aLeasedPortIsAValidPortNumber() {
        assertThatThrownBy(() -> Lease.port(0, "alice", true, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Lease.port(65_536, "alice", true, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Lease.port(65_535, "alice", true, NOW).key()).isEqualTo("65535");
    }
}
