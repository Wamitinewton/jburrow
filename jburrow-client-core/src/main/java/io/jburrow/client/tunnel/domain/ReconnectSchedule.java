package io.jburrow.client.tunnel.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * When the client tries again: ARCHITECTURE.md §Reconnecting, phase 4. One instance per client, confined to the
 * client's event loop.
 *
 * <p>A run of attempts starts with how the last one ended, through {@link #afterGoAway}, {@link #afterLoss},
 * {@link #afterRetryAfter} or {@link #startingUp}, and {@link #nextDelay()} gives each delay in turn:
 *
 * <ul>
 *   <li>after {@code GOAWAY}: {@code reconnectAfter}, then one unit ± 20% until 60 units have passed since the first
 *       attempt, then the backoff;
 *   <li>after a lost connection: uniform in 0 to half a unit, then the backoff;
 *   <li>after a refusal with {@code Retry-After}: {@code Retry-After}, then the backoff, never below it;
 *   <li>while the first session is starting: one unit ± 20%, at least what the last refusal asked for, until 60 units
 *       have passed since the first failure, then nothing.
 * </ul>
 *
 * <p>The backoff is 1, 2, 4, 8, 16, then 30 units, each {@code d/2 + uniform(0, d/2)}. It carries on across sessions:
 * only a session that stayed established for 60 units resets it, so a server that accepts sessions and drops them at
 * once is never retried faster than the backoff. Until then a lost connection's quick first attempt is the backoff's
 * next delay instead.
 *
 * <p>The unit is one second in {@code ReconnectPolicy.standard()}; tests shorten it. The server's own values,
 * {@code reconnectAfterMs} and {@code Retry-After}, are never scaled, and a {@code Retry-After} counts for at most 60
 * seconds, the bound the protocol puts on {@code reconnectAfterMs}.
 */
public final class ReconnectSchedule {

    public static final Duration STANDARD_UNIT = Duration.ofSeconds(1);
    public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private static final int MAX_BACKOFF_UNITS = 30;
    private static final int WINDOW_UNITS = 60;

    private enum Mode {
        NONE,
        GOAWAY,
        BACKOFF,
        STARTING
    }

    private final long unit;
    private final RandomGenerator random;
    private final InstantSource clock;

    private Mode mode = Mode.NONE;
    /** The delay {@link #nextDelay()} returns next, set by the method that started the run; -1 for none. */
    private long first = -1;
    /** When the run's cadence ends: 60 units after a {@code GOAWAY} run's first attempt, or a start-up's first failure. */
    private Instant cadenceEnds;
    /** The least any delay of the run may be: a {@code Retry-After}. */
    private long floor;
    /** The backoff delays given since the last stable session. */
    private int level;
    /** No run has started since the last stable session, so a lost connection may be retried at once. */
    private boolean fresh = true;

    private Instant establishedAt;

    /**
     * @param unit what one second of the schedule lasts: {@link #STANDARD_UNIT}, or shorter for tests
     */
    public ReconnectSchedule(Duration unit, RandomGenerator random, InstantSource clock) {
        Objects.requireNonNull(unit, "unit");
        if (unit.isNegative() || unit.isZero() || unit.compareTo(STANDARD_UNIT) > 0) {
            throw new IllegalArgumentException("The schedule's unit must be positive and at most one second");
        }
        this.unit = unit.toNanos();
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A session ended after the server's {@code GOAWAY}, which asked for at least {@code reconnectAfter}. */
    public void afterGoAway(Duration reconnectAfter) {
        start(Mode.GOAWAY, nanos(reconnectAfter, "reconnectAfter"));
    }

    /** A session ended without a {@code GOAWAY}: a lost connection, a heartbeat timeout, a retryable error. */
    public void afterLoss() {
        start(Mode.BACKOFF, fresh ? random.nextLong(0, unit / 2 + 1) : backoff());
    }

    /** An attempt was refused with {@code Retry-After}: that long first, and never less until the run ends. */
    public void afterRetryAfter(Duration retryAfter) {
        long asked = Math.min(nanos(retryAfter, "retryAfter"), MAX_RETRY_AFTER.toNanos());
        start(Mode.BACKOFF, fresh ? asked : Math.max(asked, backoff()));
        floor = asked;
    }

    /**
     * The first session failed in a way a deploy explains. The first call starts a run on the {@code GOAWAY} cadence
     * that lasts 60 units; later calls carry it on. The next delay is at least {@code atLeast}, a refusal's
     * {@code Retry-After}, or zero.
     */
    public void startingUp(Duration atLeast) {
        long asked = Math.min(nanos(atLeast, "atLeast"), MAX_RETRY_AFTER.toNanos());
        if (mode != Mode.STARTING) {
            mode = Mode.STARTING;
            cadenceEnds = clock.instant().plusNanos(WINDOW_UNITS * unit);
            fresh = false;
        }
        first = Math.max(asked, cadence());
    }

    /**
     * The delay before the next attempt of the current run, which advances it. Empty only once a start-up run has
     * lasted its 60 units: the first session gives up.
     */
    public Optional<Duration> nextDelay() {
        if (mode == Mode.STARTING && !clock.instant().isBefore(cadenceEnds)) {
            first = -1;
            return Optional.empty();
        }
        long delay;
        if (first >= 0) {
            delay = first;
            first = -1;
            if (mode == Mode.GOAWAY) {
                cadenceEnds = clock.instant().plusNanos(delay + WINDOW_UNITS * unit);
            }
        } else if (mode == Mode.STARTING) {
            delay = cadence();
        } else if (mode == Mode.GOAWAY && clock.instant().isBefore(cadenceEnds)) {
            delay = cadence();
        } else {
            mode = Mode.BACKOFF;
            delay = Math.max(floor, backoff());
        }
        return Optional.of(Duration.ofNanos(delay));
    }

    /** A session was established; it resets the backoff if it lasts 60 units. */
    public void established() {
        establishedAt = clock.instant();
    }

    /** The established session ended. */
    public void ended() {
        if (establishedAt != null && !clock.instant().isBefore(establishedAt.plusNanos(WINDOW_UNITS * unit))) {
            level = 0;
            fresh = true;
        }
        establishedAt = null;
    }

    private void start(Mode next, long firstDelay) {
        mode = next;
        first = firstDelay;
        floor = 0;
        fresh = false;
    }

    /** The backoff's next delay, which advances it. */
    private long backoff() {
        long base = Math.min(unit << Math.min(level, 5), MAX_BACKOFF_UNITS * unit);
        level++;
        return base / 2 + random.nextLong(0, base / 2 + 1);
    }

    /** One unit ± 20%. */
    private long cadence() {
        return random.nextLong(unit * 8 / 10, unit * 12 / 10 + 1);
    }

    private static long nanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration.toNanos();
    }
}
