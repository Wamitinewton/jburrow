package io.jburrow.server.core.portpool.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.MutableClock;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PortReservationServiceTest {

    private final PortReservationService ports = new PortReservationService(new PortPool(
            new PortRange(21_000, 21_001),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            new Random(7),
            new MutableClock()));

    @Test
    void reservesTheRequestedPort() {
        assertThat(ports.reserve(OptionalInt.of(21_001)).port()).isEqualTo(21_001);
    }

    @Test
    void reservesAnyPortWhenNoneIsRequested() {
        assertThat(ports.reserve(OptionalInt.empty()).port()).isBetween(21_000, 21_001);
    }

    @Test
    void aRequestedPortOutsideTheRangeIsUnavailableAndTheMessageNamesTheRange() {
        assertThatThrownBy(() -> ports.reserve(OptionalInt.of(20_417)))
                .isInstanceOfSatisfying(ProtocolException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.PORT_UNAVAILABLE);
                    assertThat(refused.scope()).isEqualTo(ErrorScope.SESSION);
                })
                .hasMessageContaining("21000-21001");
    }

    @Test
    void aRequestedPortInUseIsUnavailable() {
        ports.reserve(OptionalInt.of(21_000));

        assertThatThrownBy(() -> ports.reserve(OptionalInt.of(21_000)))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.PORT_UNAVAILABLE));
    }

    @Test
    void anEmptyPoolIsExhausted() {
        ports.reserve(OptionalInt.empty());
        ports.reserve(OptionalInt.empty());

        assertThatThrownBy(() -> ports.reserve(OptionalInt.empty()))
                .isInstanceOfSatisfying(
                        ProtocolException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.PORT_POOL_EXHAUSTED));
    }

    @Test
    void releaseAndQuarantineReachThePool() {
        Reservation released = ports.reserve(OptionalInt.of(21_000));
        Reservation quarantined = ports.reserve(OptionalInt.of(21_001));

        ports.release(released);
        ports.quarantine(quarantined);

        assertThat(ports.snapshot()).isEqualTo(new PortPoolSnapshot(1, 0, 1));
    }
}
