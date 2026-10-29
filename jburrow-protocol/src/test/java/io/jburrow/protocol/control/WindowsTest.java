package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WindowsTest {

    @Test
    void acceptsTheReferenceWindows() {
        Windows client = new Windows(262_144, 1_048_576);
        Windows server = new Windows(262_144, 524_288);

        assertThat(client.stream()).isEqualTo(262_144);
        assertThat(server.session()).isEqualTo(524_288);
    }

    @Test
    void streamWindowIsAtLeastOneFullDataPayload() {
        new Windows(65_536, 65_536);
        String rule = "stream must be between 65536 and 2147483647";
        assertInvalid(() -> new Windows(65_535, 65_536), rule);
        assertInvalid(() -> new Windows(1, 65_536), rule);
        assertInvalid(() -> new Windows(0, 65_536), rule);
        assertInvalid(() -> new Windows(-1, 65_536), rule);
        assertInvalid(() -> new Windows(Integer.MIN_VALUE, 65_536), rule);
    }

    @Test
    void sessionWindowIsAtLeastTheStreamWindow() {
        new Windows(262_144, 262_144);
        String rule = "session must be at least stream";
        assertInvalid(() -> new Windows(262_144, 262_143), rule);
        assertInvalid(() -> new Windows(65_536, 0), rule);
        assertInvalid(() -> new Windows(65_536, -1), rule);
    }

    @Test
    void bothWindowsMayBeTheLargestCredit() {
        Windows largest = new Windows(Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(largest.session()).isEqualTo(2_147_483_647);
    }

    @Test
    void theStreamRuleIsCheckedFirst() {
        assertInvalid(() -> new Windows(65_535, 1), "stream must be between 65536 and 2147483647");
    }
}
