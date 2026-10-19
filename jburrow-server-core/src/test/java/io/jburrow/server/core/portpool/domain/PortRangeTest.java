package io.jburrow.server.core.portpool.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PortRangeTest {

    @Test
    void parsesFirstAndLast() {
        PortRange range = PortRange.parse("20000-20999");

        assertThat(range).isEqualTo(new PortRange(20_000, 20_999));
        assertThat(range.size()).isEqualTo(1_000);
        assertThat(range).hasToString("20000-20999");
    }

    @Test
    void aSinglePortIsARange() {
        assertThat(PortRange.parse("21000-21000").size()).isEqualTo(1);
    }

    @Test
    void containsBothEnds() {
        PortRange range = new PortRange(21_000, 21_099);

        assertThat(range.contains(21_000)).isTrue();
        assertThat(range.contains(21_099)).isTrue();
        assertThat(range.contains(20_999)).isFalse();
        assertThat(range.contains(21_100)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "20000", "20000-", "-20999", "20000 - 20999", "a-b", "20000-20999-21000", "123456-1"})
    void rejectsMalformedText(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> PortRange.parse(text));
    }

    @Test
    void rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> PortRange.parse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1023-2000", "60000-65536", "0-100", "21000-20999"})
    void rejectsRangesOutsideTheUnprivilegedPortsOrBackwards(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> PortRange.parse(text));
    }

    @Test
    void acceptsTheWholeUnprivilegedRange() {
        assertThat(PortRange.parse("1024-65535").size()).isEqualTo(64_512);
    }
}
