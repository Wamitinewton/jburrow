package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.control.GoAway;
import java.time.Duration;
import java.util.Objects;

/**
 * How the edge drains when it stops ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain), built by the
 * composition root from {@code jburrow.drain.*}.
 *
 * @param timeout the longest the drain waits for open streams; {@code drainMs} in {@code GOAWAY}
 * @param reconnectSpread each session's {@code reconnectAfterMs} is drawn uniformly from 0 to this
 */
public record DrainSettings(Duration timeout, Duration reconnectSpread) {

    /** The defaults of {@code jburrow.drain.*}. */
    public static final DrainSettings DEFAULTS = new DrainSettings(Duration.ofSeconds(5), Duration.ofSeconds(2));

    /** @throws IllegalArgumentException if {@code timeout} isn't positive, or either value can't be sent in GOAWAY */
    public DrainSettings {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(reconnectSpread, "reconnectSpread");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The drain timeout must be positive: " + timeout);
        }
        new GoAway(reconnectSpread.toMillis(), timeout.toMillis());
    }
}
