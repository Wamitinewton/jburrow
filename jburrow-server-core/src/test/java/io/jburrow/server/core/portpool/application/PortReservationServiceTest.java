package io.jburrow.server.core.portpool.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortClaimSnapshot;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.MutableClock;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PortReservationServiceTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final Duration WINDOW = Duration.ofSeconds(120);

    private final MutableClock clock = new MutableClock();
    private final PortReservationService<String> ports = new PortReservationService<>(new PortPool<>(
            new PortRange(21_000, 21_001),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            WINDOW,
            new Random(7),
            clock));

    @Test
    void reservesTheRequestedPort() {
        assertThat(ports.reserve(OptionalInt.of(21_001), ALICE).port()).isEqualTo(21_001);
    }

    @Test
    void reservesAnyPortWhenNoneIsRequested() {
        assertThat(ports.reserve(OptionalInt.empty(), ALICE).port()).isBetween(21_000, 21_001);
    }

    @Test
    void aRequestedPortOutsideTheRangeIsUnavailableAndTheMessageNamesTheRange() {
        assertThatThrownBy(() -> ports.reserve(OptionalInt.of(20_417), ALICE))
                .isInstanceOfSatisfying(ProtocolException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.PORT_UNAVAILABLE);
                    assertThat(refused.scope()).isEqualTo(ErrorScope.SESSION);
                })
                .hasMessageContaining("21000-21001");
    }

    @Test
    void aRequestedPortInUseIsUnavailable() {
        ports.reserve(OptionalInt.of(21_000), ALICE);

        assertThatThrownBy(() -> ports.reserve(OptionalInt.of(21_000), ALICE))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.PORT_UNAVAILABLE));
    }

    @Test
    void anEmptyPoolIsExhausted() {
        ports.reserve(OptionalInt.empty(), ALICE);
        ports.reserve(OptionalInt.empty(), ALICE);

        assertThatThrownBy(() -> ports.reserve(OptionalInt.empty(), ALICE))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.PORT_POOL_EXHAUSTED));
    }

    @Test
    void releaseAndQuarantineReachThePool() {
        Reservation released = ports.reserve(OptionalInt.of(21_000), ALICE);
        Reservation quarantined = ports.reserve(OptionalInt.of(21_001), ALICE);

        ports.release(released);
        ports.quarantine(quarantined);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(0, 0, 1, 1));
    }

    @Test
    void aPortHeldForAnotherPrincipalIsUnavailable() {
        ports.release(ports.reserve(OptionalInt.of(21_000), ALICE));

        assertThatThrownBy(() -> ports.reserve(OptionalInt.of(21_000), BOB))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.PORT_UNAVAILABLE));
        assertThat(ports.reserve(OptionalInt.of(21_000), ALICE).port()).isEqualTo(21_000);
    }

    @Test
    void aSnapshotHasEveryReservedPortAndEveryHoldNotYetOver() {
        ports.reserve(OptionalInt.of(21_000), ALICE);
        ports.release(ports.reserve(OptionalInt.of(21_001), BOB));
        Instant holdEnd = clock.instant().plus(WINDOW);

        assertThat(ports.snapshot(clock.instant()))
                .containsExactly(
                        new PortClaimSnapshot<>(21_000, ALICE, null), new PortClaimSnapshot<>(21_001, BOB, holdEnd));
        assertThat(ports.snapshot(holdEnd)).containsExactly(new PortClaimSnapshot<>(21_000, ALICE, null));
    }

    @Test
    void aSeededHoldIsInTheSnapshotAndMovesTheVersion() {
        long before = ports.version();
        Instant until = clock.instant().plusSeconds(45);

        assertThat(ports.seedHold(21_001, BOB, until)).isTrue();
        assertThat(ports.seedHold(20_417, BOB, until)).isFalse();

        assertThat(ports.version()).isNotEqualTo(before);
        assertThat(ports.snapshot(clock.instant())).containsExactly(new PortClaimSnapshot<>(21_001, BOB, until));
        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(1, 0, 1, 0));
    }
}
