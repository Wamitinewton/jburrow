package io.jburrow.client.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link LocalTarget}, {@link TunnelSpec}, {@link PendingData} and {@link TunnelClosed}. */
class TunnelValuesTest {

    @Test
    void aLocalTargetPrintsAnIpv6LiteralInBrackets() {
        assertThat(LocalTarget.of("localhost", 22)).hasToString("localhost:22");
        assertThat(LocalTarget.of("::1", 22)).hasToString("[::1]:22");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "tcp://localhost", "[::1]", "local host", "a/b"})
    void aLocalTargetIsABareHost(String host) {
        assertThatThrownBy(() -> LocalTarget.of(host, 22)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65_536})
    void aLocalPortIsOneTo65535(int port) {
        assertThatThrownBy(() -> LocalTarget.of("localhost", port)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTunnelSpecBecomesTheRegisterEntry() {
        TunnelSpec spec = TunnelSpec.tcp("default", LocalTarget.of("localhost", 22));

        assertThat(spec.toRequest()).isEqualTo(new TunnelRequest("default", TunnelKind.TCP, null));
        assertThat(spec.remotePort(20_417).toRequest()).isEqualTo(new TunnelRequest("default", TunnelKind.TCP, 20_417));
        assertThat(spec.remotePort(20_417).target()).isEqualTo(spec.target());
    }

    @Test
    void aTunnelSpecFollowsTheProtocolsFieldRules() {
        LocalTarget target = LocalTarget.of("localhost", 22);

        assertThatThrownBy(() -> TunnelSpec.tcp("Default", target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ref");
        assertThatThrownBy(() -> TunnelSpec.tcp("default", target).remotePort(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remotePort");
    }

    @Test
    void pendingDataReportsTheMomentItPassesItsLimitOnce() {
        PendingData pending = new PendingData(10);

        assertThat(pending.add(10)).isFalse();
        assertThat(pending.isOverLimit()).isFalse();
        assertThat(pending.add(1)).isTrue();
        assertThat(pending.add(5)).isFalse();
        assertThat(pending.bytes()).isEqualTo(16);
        assertThat(pending.isOverLimit()).isTrue();
    }

    @Test
    void clearingPendingDataSaysWhetherReadingWasPaused() {
        PendingData pending = new PendingData(10);
        pending.add(4);

        assertThat(pending.clear()).isFalse();
        pending.add(11);
        assertThat(pending.clear()).isTrue();
        assertThat(pending.bytes()).isZero();
        assertThat(pending.isOverLimit()).isFalse();
    }

    @Test
    void aHeartbeatTimeoutFromEitherSideIsItsOwnReason() {
        assertThat(TunnelClosed.serverError(ErrorCode.HEARTBEAT_TIMEOUT, "silent")
                        .reason())
                .isEqualTo(TunnelClosed.Reason.HEARTBEAT_TIMEOUT);
        assertThat(TunnelClosed.localError(ErrorCode.HEARTBEAT_TIMEOUT, "silent")
                        .reason())
                .isEqualTo(TunnelClosed.Reason.HEARTBEAT_TIMEOUT);
        assertThat(TunnelClosed.serverError(ErrorCode.SERVER_SHUTTING_DOWN, "bye"))
                .isEqualTo(new TunnelClosed(
                        TunnelClosed.Reason.SERVER_ERROR, Optional.of(ErrorCode.SERVER_SHUTTING_DOWN), "bye"));
        assertThat(TunnelClosed.localError(ErrorCode.PROTOCOL_ERROR, "bad").reason())
                .isEqualTo(TunnelClosed.Reason.LOCAL_ERROR);
    }
}
