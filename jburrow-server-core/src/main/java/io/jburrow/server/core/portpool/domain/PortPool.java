package io.jburrow.server.core.portpool.domain;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.random.RandomGenerator;

/**
 * The state of every port in a {@link PortRange}: free, reserved by one holder for a principal, held for a principal
 * after its reservation ended, or quarantined.
 *
 * <p>Each reservation gets a new holder number. {@link #release} and {@link #quarantine} act only when the holder they
 * are given still holds the port, so a late or duplicate release can never free a port someone else now holds.
 *
 * <p>A released port is <em>held</em> for the principal that reserved it until the hold window has passed, so its
 * owner can take it back after a reconnect and nobody else can in the meantime. A held port is never drawn at random.
 * When the hold ends the port is free again, counted as released when the hold began, so it has usually cooled
 * already.
 *
 * <p>{@link #reserveAny} prefers ports that have been free for at least the reuse delay, so a port a tunnel just gave
 * up isn't handed straight to a stranger while its old visitors may still be connecting to it. A port leaving
 * quarantine counts as released when its quarantine ends.
 *
 * <p>Shared by every session, so its methods are {@code synchronized}. Free ports are kept in two index sets, cooled
 * and warming, so a reservation costs O(1) amortised rather than a scan of the range under the lock. Held and
 * quarantined ports sit in neither set until a queue by end time moves them back.
 *
 * @param <P> who reserves a port; only its {@code equals} matters here
 */
public final class PortPool<P> {

    /**
     * A reserved port and the holder number that must be presented to release it.
     *
     * @param reclaimed whether the port was held for the same principal, which took it back
     */
    public record Held(int port, long holder, boolean reclaimed) {}

    /** How many ports are in each state. */
    public record Snapshot(int free, int reserved, int held, int quarantined) {}

    /**
     * A port claimed for {@code principal}: reserved when {@code heldUntilMillis} is {@link #ACTIVE}, else held until
     * then.
     */
    public record Claim<P>(int port, P principal, long heldUntilMillis) {

        public static final long ACTIVE = Long.MIN_VALUE;

        public Claim {
            Objects.requireNonNull(principal, "principal");
        }

        public boolean active() {
            return heldUntilMillis == ACTIVE;
        }
    }

    private enum State {
        FREE,
        RESERVED,
        HELD,
        QUARANTINED
    }

    /** A port that changes state at {@code at} if it is still in the state that queued it. */
    private record Due(int offset, long at) {}

    private static final long NOT_HELD = 0;

    private final PortRange range;
    private final long reuseDelayMillis;
    private final long quarantineMillis;
    private final long holdMillis;
    private final RandomGenerator random;
    private final InstantSource clock;

    private final State[] states;
    private final long[] holders;
    /**
     * For a free port, when it was released; for a held port, when its hold ends; for a quarantined port, when its
     * quarantine ends.
     */
    private final long[] stamps;
    /** The principal of a reserved or held port. */
    private final Object[] principals;

    private final OffsetSet cooled;
    private final OffsetSet warming;
    private final ArrayDeque<Due> warmingQueue = new ArrayDeque<>();
    private final ArrayDeque<Due> quarantineQueue = new ArrayDeque<>();
    /** By end time, not arrival: a seeded hold may end before or after one released since. */
    private final PriorityQueue<Due> holdQueue = new PriorityQueue<>(Comparator.comparingLong(Due::at));

    private int reserved;
    private int held;
    private int quarantined;
    private long lastHolder = NOT_HELD;
    private long version;

    /** @param hold how long a released port stays held for its principal; zero turns holds off */
    public PortPool(
            PortRange range,
            Duration reuseDelay,
            Duration quarantine,
            Duration hold,
            RandomGenerator random,
            InstantSource clock) {
        this.range = Objects.requireNonNull(range, "range");
        this.reuseDelayMillis = nonNegativeMillis(reuseDelay, "reuseDelay");
        this.quarantineMillis = nonNegativeMillis(quarantine, "quarantine");
        this.holdMillis = nonNegativeMillis(hold, "hold");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");

        int size = range.size();
        this.states = new State[size];
        this.holders = new long[size];
        this.stamps = new long[size];
        this.principals = new Object[size];
        this.cooled = new OffsetSet(size);
        this.warming = new OffsetSet(size);
        Arrays.fill(states, State.FREE);
        for (int offset = 0; offset < size; offset++) {
            cooled.add(offset);
        }
    }

    public PortRange range() {
        return range;
    }

    /**
     * Reserves {@code port} for {@code principal} if it is in the range and either free, however recently it was
     * released, or held for {@code principal}.
     */
    public synchronized Optional<Held> reserveRequested(int port, P principal) {
        Objects.requireNonNull(principal, "principal");
        if (!range.contains(port)) {
            return Optional.empty();
        }
        refresh(clock.millis());
        int offset = port - range.first();
        return switch (states[offset]) {
            case FREE -> Optional.of(reserve(offset, principal, false));
            case HELD -> {
                if (!principals[offset].equals(principal)) {
                    yield Optional.empty();
                }
                held--;
                yield Optional.of(reserve(offset, principal, true));
            }
            case RESERVED, QUARANTINED -> Optional.empty();
        };
    }

    /**
     * Reserves for {@code principal} a port chosen uniformly at random among those free for at least the reuse delay,
     * or, when there are none, among every free port. Never a held port. Empty when no port is free.
     */
    public synchronized Optional<Held> reserveAny(P principal) {
        Objects.requireNonNull(principal, "principal");
        refresh(clock.millis());
        OffsetSet from = cooled.size() > 0 ? cooled : warming;
        if (from.size() == 0) {
            return Optional.empty();
        }
        return Optional.of(reserve(from.get(random.nextInt(from.size())), principal, false));
    }

    /**
     * Ends the reservation if {@code holder} still holds {@code port}, and holds the port for its principal for the
     * hold window, or frees it at once when the window is zero; otherwise does nothing.
     */
    public synchronized void release(int port, long holder) {
        long now = clock.millis();
        refresh(now);
        int offset = heldOffset(port, holder);
        if (offset < 0) {
            return;
        }
        holders[offset] = NOT_HELD;
        reserved--;
        if (holdMillis == 0) {
            principals[offset] = null;
            free(offset, now, now);
        } else {
            hold(offset, now + holdMillis);
        }
        version++;
    }

    /**
     * Holds {@code port} out of the pool for the quarantine period if {@code holder} still holds it; otherwise does
     * nothing. For a port whose bind failed because something else on the host uses it. A quarantined port is held for
     * nobody.
     */
    public synchronized void quarantine(int port, long holder) {
        long now = clock.millis();
        refresh(now);
        int offset = heldOffset(port, holder);
        if (offset < 0) {
            return;
        }
        holders[offset] = NOT_HELD;
        principals[offset] = null;
        reserved--;
        long until = now + quarantineMillis;
        states[offset] = State.QUARANTINED;
        stamps[offset] = until;
        quarantined++;
        quarantineQueue.addLast(new Due(offset, until));
        version++;
    }

    /**
     * Holds {@code port} for {@code principal} until {@code untilMillis}, if it is in the range, free, and the hold
     * hasn't already ended. Never replaces a reservation, a hold or a quarantine. For holds carried over from a previous
     * server process.
     *
     * @return whether the hold was added
     */
    public synchronized boolean seedHold(int port, P principal, long untilMillis) {
        Objects.requireNonNull(principal, "principal");
        long now = clock.millis();
        refresh(now);
        if (!range.contains(port) || untilMillis <= now) {
            return false;
        }
        int offset = port - range.first();
        if (states[offset] != State.FREE) {
            return false;
        }
        if (cooled.contains(offset)) {
            cooled.remove(offset);
        } else {
            warming.remove(offset);
        }
        principals[offset] = principal;
        hold(offset, untilMillis);
        version++;
        return true;
    }

    /** Every reserved port and every hold not yet ended, in port order. */
    @SuppressWarnings("unchecked")
    public synchronized List<Claim<P>> claims() {
        refresh(clock.millis());
        List<Claim<P>> claims = new ArrayList<>(reserved + held);
        for (int offset = 0; offset < states.length; offset++) {
            switch (states[offset]) {
                case RESERVED -> claims.add(new Claim<>(range.first() + offset, (P) principals[offset], Claim.ACTIVE));
                case HELD -> claims.add(new Claim<>(range.first() + offset, (P) principals[offset], stamps[offset]));
                case FREE, QUARANTINED -> {}
            }
        }
        return claims;
    }

    /** Changes with every reservation, release, quarantine, seed and hold that ends; equal values mean no change. */
    public synchronized long version() {
        refresh(clock.millis());
        return version;
    }

    public synchronized Snapshot snapshot() {
        refresh(clock.millis());
        return new Snapshot(cooled.size() + warming.size(), reserved, held, quarantined);
    }

    private Held reserve(int offset, P principal, boolean reclaimed) {
        if (cooled.contains(offset)) {
            cooled.remove(offset);
        } else if (warming.contains(offset)) {
            warming.remove(offset);
        }
        states[offset] = State.RESERVED;
        holders[offset] = ++lastHolder;
        principals[offset] = principal;
        reserved++;
        version++;
        return new Held(range.first() + offset, lastHolder, reclaimed);
    }

    private void hold(int offset, long until) {
        states[offset] = State.HELD;
        stamps[offset] = until;
        held++;
        holdQueue.add(new Due(offset, until));
    }

    private int heldOffset(int port, long holder) {
        if (!range.contains(port)) {
            throw new IllegalArgumentException("Port " + port + " is outside the range " + range);
        }
        int offset = port - range.first();
        return states[offset] == State.RESERVED && holders[offset] == holder ? offset : -1;
    }

    /** A port released long enough ago, as one whose hold has just ended usually was, goes straight to cooled. */
    private void free(int offset, long releasedAt, long now) {
        states[offset] = State.FREE;
        stamps[offset] = releasedAt;
        if (releasedAt + reuseDelayMillis <= now) {
            cooled.add(offset);
        } else {
            warming.add(offset);
            warmingQueue.addLast(new Due(offset, releasedAt));
        }
    }

    /**
     * Ends every hold and quarantine that is over, then moves every port free for the reuse delay into the cooled set. A
     * queue entry whose port has changed state since it was queued is stale and dropped.
     */
    private void refresh(long now) {
        while (!holdQueue.isEmpty() && holdQueue.peek().at() <= now) {
            Due due = holdQueue.poll();
            if (states[due.offset()] == State.HELD && stamps[due.offset()] == due.at()) {
                held--;
                principals[due.offset()] = null;
                free(due.offset(), due.at() - holdMillis, now);
                version++;
            }
        }
        while (!quarantineQueue.isEmpty() && quarantineQueue.peekFirst().at() <= now) {
            Due due = quarantineQueue.removeFirst();
            if (states[due.offset()] == State.QUARANTINED && stamps[due.offset()] == due.at()) {
                quarantined--;
                free(due.offset(), due.at(), now);
            }
        }
        while (!warmingQueue.isEmpty() && warmingQueue.peekFirst().at() + reuseDelayMillis <= now) {
            Due due = warmingQueue.removeFirst();
            if (warming.contains(due.offset()) && stamps[due.offset()] == due.at()) {
                warming.remove(due.offset());
                cooled.add(due.offset());
            }
        }
    }

    private static long nonNegativeMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative: " + duration);
        }
        return duration.toMillis();
    }

    /** A set of offsets into the range with O(1) add, remove, membership and indexed access, by swap-remove. */
    private static final class OffsetSet {

        private static final int ABSENT = -1;

        private final int[] members;
        private final int[] positions;
        private int size;

        OffsetSet(int capacity) {
            members = new int[capacity];
            positions = new int[capacity];
            Arrays.fill(positions, ABSENT);
        }

        int size() {
            return size;
        }

        int get(int index) {
            return members[index];
        }

        boolean contains(int offset) {
            return positions[offset] != ABSENT;
        }

        void add(int offset) {
            positions[offset] = size;
            members[size++] = offset;
        }

        void remove(int offset) {
            int position = positions[offset];
            int last = members[--size];
            members[position] = last;
            positions[last] = position;
            positions[offset] = ABSENT;
        }
    }
}
