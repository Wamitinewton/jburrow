package io.jburrow.protocol.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolVersionTest {

    @Test
    void currentIsTheFirstDraft() {
        assertThat(ProtocolVersion.CURRENT).isEqualTo("jburrow.v1-draft.1");
        assertThat(ProtocolVersion.supported()).containsExactly("jburrow.v1-draft.1");
    }

    @Test
    void parsesACommaSeparatedHeader() {
        assertThat(ProtocolVersion.parseHeader("jburrow.v1-draft.2, jburrow.v1-draft.1"))
                .containsExactly("jburrow.v1-draft.2", "jburrow.v1-draft.1");
    }

    @Test
    void trimsSpacesAndTabs() {
        assertThat(ProtocolVersion.parseHeader("  jburrow.v1-draft.1 ,\tother  "))
                .containsExactly("jburrow.v1-draft.1", "other");
    }

    @Test
    void dropsEmptyEntries() {
        assertThat(ProtocolVersion.parseHeader(",, jburrow.v1-draft.1 , ,")).containsExactly("jburrow.v1-draft.1");
        assertThat(ProtocolVersion.parseHeader("")).isEmpty();
        assertThat(ProtocolVersion.parseHeader(" , ")).isEmpty();
        assertThat(ProtocolVersion.parseHeader(null)).isEmpty();
    }

    @Test
    void dropsRepeatsKeepingTheFirstPosition() {
        assertThat(ProtocolVersion.parseHeader("a, jburrow.v1-draft.1, a, jburrow.v1-draft.1"))
                .containsExactly("a", "jburrow.v1-draft.1");
    }

    @Test
    void selectsTheFirstSupportedOffer() {
        assertThat(ProtocolVersion.select(List.of("jburrow.v1-draft.9", "jburrow.v1-draft.1")))
                .contains("jburrow.v1-draft.1");
    }

    @Test
    void selectsNothingWhenNoOfferIsSupported() {
        assertThat(ProtocolVersion.select(List.of("jburrow.v1-draft.2", "chat")))
                .isEmpty();
        assertThat(ProtocolVersion.select(List.of())).isEmpty();
    }

    @Test
    void tokensCompareExactly() {
        assertThat(ProtocolVersion.select(List.of("JBURROW.V1-DRAFT.1"))).isEmpty();
    }
}
