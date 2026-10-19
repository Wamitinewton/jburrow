package io.jburrow.protocol.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TunnelKindTest {

    @Test
    void tcpIsTheOnlyKindInThisDraft() {
        assertThat(TunnelKind.values()).containsExactly(TunnelKind.TCP);
        assertThat(TunnelKind.TCP.wireName()).isEqualTo("tcp");
    }

    @Test
    void fromWireNameIsExact() {
        assertThat(TunnelKind.fromWireName("tcp")).contains(TunnelKind.TCP);
        assertThat(TunnelKind.fromWireName("TCP")).isEmpty();
        assertThat(TunnelKind.fromWireName(" tcp")).isEmpty();
        assertThat(TunnelKind.fromWireName("http")).isEmpty();
        assertThat(TunnelKind.fromWireName("")).isEmpty();
        assertThat(TunnelKind.fromWireName(null)).isEmpty();
    }
}
