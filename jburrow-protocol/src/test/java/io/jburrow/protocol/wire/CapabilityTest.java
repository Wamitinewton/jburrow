package io.jburrow.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityTest {

    @Test
    void httpIsTheOnlyCapability() {
        assertThat(Capability.values()).containsExactly(Capability.HTTP);
        assertThat(Capability.HTTP.wireName()).isEqualTo("http");
        assertThat(ProtocolVersion.capabilities()).containsExactly(Capability.HTTP);
    }

    @Test
    void wireNamesFollowTheCapabilityRule() {
        for (Capability capability : Capability.values()) {
            assertThat(capability.wireName()).matches("[a-z0-9.-]{1,64}");
        }
    }

    @Test
    void fromWireNameIsExact() {
        assertThat(Capability.fromWireName("http")).contains(Capability.HTTP);
        assertThat(Capability.fromWireName("HTTP")).isEmpty();
        assertThat(Capability.fromWireName("http ")).isEmpty();
        assertThat(Capability.fromWireName("https")).isEmpty();
        assertThat(Capability.fromWireName("")).isEmpty();
        assertThat(Capability.fromWireName(null)).isEmpty();
    }

    @Test
    void negotiationAnswersWithTheOfferedCapabilitiesThatAreEnabled() {
        assertThat(ProtocolVersion.negotiate(List.of("http"), Set.of(Capability.HTTP)))
                .containsExactly("http");
    }

    @Test
    void negotiationIgnoresUnknownNames() {
        assertThat(ProtocolVersion.negotiate(
                        List.of("compression", "http", "tls-passthrough"), Set.of(Capability.HTTP)))
                .containsExactly("http");
        assertThat(ProtocolVersion.negotiate(List.of("HTTP", "future"), Set.of(Capability.HTTP)))
                .isEmpty();
    }

    @Test
    void negotiationLeavesOutWhatTheServerHasNotEnabled() {
        assertThat(ProtocolVersion.negotiate(List.of("http"), EnumSet.noneOf(Capability.class)))
                .isEmpty();
    }

    @Test
    void nothingOfferedIsNothingAgreed() {
        assertThat(ProtocolVersion.negotiate(List.of(), Set.of(Capability.HTTP)))
                .isEmpty();
    }
}
