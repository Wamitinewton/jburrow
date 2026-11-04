package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoAwayTest {

    @Test
    void acceptsTheArchitectureExample() {
        GoAway goAway = new GoAway(830, 5_000);

        assertThat(goAway.reconnectAfterMs()).isEqualTo(830);
        assertThat(goAway.drainMs()).isEqualTo(5_000);
    }

    @Test
    void reconnectAfterMsIsZeroToSixtySeconds() {
        new GoAway(0, 5_000);
        new GoAway(60_000, 5_000);
        assertInvalid(() -> new GoAway(-1, 5_000), "reconnectAfterMs must be between 0 and 60000");
        assertInvalid(() -> new GoAway(60_001, 5_000), "reconnectAfterMs must be between 0 and 60000");
        assertInvalid(() -> new GoAway(Long.MIN_VALUE, 5_000), "reconnectAfterMs must be between 0 and 60000");
    }

    @Test
    void drainMsIsZeroToFiveMinutes() {
        new GoAway(830, 0);
        new GoAway(830, 300_000);
        assertInvalid(() -> new GoAway(830, -1), "drainMs must be between 0 and 300000");
        assertInvalid(() -> new GoAway(830, 300_001), "drainMs must be between 0 and 300000");
        assertInvalid(() -> new GoAway(830, Long.MAX_VALUE), "drainMs must be between 0 and 300000");
    }
}
