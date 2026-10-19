package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionLimitsTest {

    @Test
    void acceptsTheArchitectureExample() {
        SessionLimits limits = new SessionLimits(65_536, 1, 1, 15_000, 45_000);

        assertThat(limits.idleTimeoutMs()).isEqualTo(45_000);
    }

    @Test
    void maxFramePayloadMayOnlyLowerTheDataCap() {
        new SessionLimits(1, 1, 1, 15_000, 45_000);
        new SessionLimits(65_536, 1, 1, 15_000, 45_000);
        String rule = "maxFramePayload must be between 1 and 65536";
        assertInvalid(() -> new SessionLimits(0, 1, 1, 15_000, 45_000), rule);
        assertInvalid(() -> new SessionLimits(65_537, 1, 1, 15_000, 45_000), rule);
    }

    @Test
    void tunnelAndStreamLimitsAreAtLeastOne() {
        new SessionLimits(65_536, Integer.MAX_VALUE, Integer.MAX_VALUE, 15_000, 45_000);
        assertInvalid(
                () -> new SessionLimits(65_536, 0, 1, 15_000, 45_000), "maxTunnels must be between 1 and 2147483647");
        assertInvalid(
                () -> new SessionLimits(65_536, 1, 0, 15_000, 45_000),
                "maxConcurrentStreams must be between 1 and 2147483647");
    }

    @Test
    void pingIntervalIsAtLeastOneSecond() {
        new SessionLimits(65_536, 1, 1, 1_000, 2_000);
        assertInvalid(() -> new SessionLimits(65_536, 1, 1, 999, 2_000), "pingIntervalMs must be at least 1000");
    }

    @Test
    void idleTimeoutIsAtLeastTwicePingInterval() {
        new SessionLimits(65_536, 1, 1, 15_000, 30_000);
        new SessionLimits(65_536, 1, 1, 1_001, 2_002);
        String rule = "idleTimeoutMs must be at least twice pingIntervalMs";
        assertInvalid(() -> new SessionLimits(65_536, 1, 1, 15_000, 29_999), rule);
        assertInvalid(() -> new SessionLimits(65_536, 1, 1, 1_001, 2_001), rule);
        assertInvalid(() -> new SessionLimits(65_536, 1, 1, 1_000, -1), rule);
    }

    @Test
    void idleTimeoutCheckDoesNotOverflow() {
        new SessionLimits(65_536, 1, 1, Long.MAX_VALUE / 2, Long.MAX_VALUE);
        assertInvalid(
                () -> new SessionLimits(65_536, 1, 1, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE),
                "idleTimeoutMs must be at least twice pingIntervalMs");
    }
}
