package io.jburrow.client;

import io.jburrow.client.tunnel.domain.ReconnectSchedule;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Whether a client starts another session when one ends. {@link #standard()}, the default, reconnects for as long as
 * the client runs, unless the server says retrying can't help, on a fixed schedule with jitter and backoff; {@link
 * #never()} ends the client with its first session.
 *
 * <p>The schedule isn't configurable: one client's backoff is the server's protection against many clients. Only a
 * test may shorten it, with {@link #scaled}.
 */
public final class ReconnectPolicy {

    private static final ReconnectPolicy STANDARD = new ReconnectPolicy(ReconnectSchedule.STANDARD_UNIT);
    private static final ReconnectPolicy NEVER = new ReconnectPolicy(null);
    private static final Duration SHORTEST_UNIT = Duration.ofMillis(1);

    /** What one second of the schedule lasts; null for {@link #never()}. */
    private final Duration unit;

    private ReconnectPolicy(Duration unit) {
        this.unit = unit;
    }

    /** Reconnect with the standard schedule. */
    public static ReconnectPolicy standard() {
        return STANDARD;
    }

    /** Don't reconnect: the client stops when its first session ends, as before phase 4. */
    public static ReconnectPolicy never() {
        return NEVER;
    }

    /**
     * The standard schedule with each of its seconds lasting {@code second} instead, so a test can watch a backoff
     * without waiting for it. The server's own delays, {@code GOAWAY}'s and {@code Retry-After}, are never shortened.
     *
     * @param second between 1 ms and 1 s
     * @throws IllegalStateException on {@link #never()}
     */
    public ReconnectPolicy scaled(Duration second) {
        Objects.requireNonNull(second, "second");
        if (unit == null) {
            throw new IllegalStateException("A policy that never reconnects has no schedule to scale");
        }
        if (second.compareTo(SHORTEST_UNIT) < 0 || second.compareTo(ReconnectSchedule.STANDARD_UNIT) > 0) {
            throw new IllegalArgumentException("A scaled second must be between 1 ms and 1 s");
        }
        return new ReconnectPolicy(second);
    }

    /** Whether the client tries another session when one ends. */
    public boolean reconnects() {
        return unit != null;
    }

    /** A new schedule for one client, empty for {@link #never()}. */
    public Optional<ReconnectSchedule> newSchedule(RandomGenerator random, InstantSource clock) {
        return unit == null ? Optional.empty() : Optional.of(new ReconnectSchedule(unit, random, clock));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReconnectPolicy policy && Objects.equals(unit, policy.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(unit);
    }

    @Override
    public String toString() {
        if (unit == null) {
            return "never";
        }
        return unit.equals(ReconnectSchedule.STANDARD_UNIT) ? "standard" : "standard, scaled to " + unit;
    }
}
