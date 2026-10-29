package io.jburrow.protocol.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.wire.Capability;
import org.junit.jupiter.api.Test;

class TunnelKindTest {

    @Test
    void tcpIsTheDraftsKindAndHttpTheCapabilitys() {
        assertThat(TunnelKind.values()).containsExactly(TunnelKind.TCP, TunnelKind.HTTP);
        assertThat(TunnelKind.TCP.wireName()).isEqualTo("tcp");
        assertThat(TunnelKind.TCP.capability()).isEmpty();
        assertThat(TunnelKind.HTTP.wireName()).isEqualTo("http");
        assertThat(TunnelKind.HTTP.capability()).contains(Capability.HTTP);
    }

    @Test
    void fromWireNameIsExact() {
        assertThat(TunnelKind.fromWireName("tcp")).contains(TunnelKind.TCP);
        assertThat(TunnelKind.fromWireName("http")).contains(TunnelKind.HTTP);
        assertThat(TunnelKind.fromWireName("TCP")).isEmpty();
        assertThat(TunnelKind.fromWireName(" tcp")).isEmpty();
        assertThat(TunnelKind.fromWireName("https")).isEmpty();
        assertThat(TunnelKind.fromWireName("")).isEmpty();
        assertThat(TunnelKind.fromWireName(null)).isEmpty();
    }
}
