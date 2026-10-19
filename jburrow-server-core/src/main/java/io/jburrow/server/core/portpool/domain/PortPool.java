package io.jburrow.server.core.portpool.domain;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The state of every port in a {@link PortRange}: free, reserved by one holder, or quarantined.
 *
 * <p>Each reservation gets a new holder number. {@link #release} and {@link #quarantine} act only when the holder they
 * are given still holds the port, so a late or duplicate release can never free a port someone else now holds.
 *
 * <p>{@link #reserveAny} prefers ports that have been free for at least the reuse delay, so a port a tunnel just gave
 * up isn't handed straight to a stranger while its old visitors may still be connecting to it. A port leaving
 * quarantine counts as released when its quarantine ends.
 *
 * <p>Shared by every session, so its methods are {@code synchronized}. Free ports are kept in two index sets, cooled
 * and warming, so a reservation costs O(1) amortised rather than a scan of the range under the lock.
 */
public final class PortPool {

    /** A reserved port and the holder number that must be presented to release it. */
    public record Held(int port, long holder) {}

    /** How many ports are in each state. */
    public record Snapshot(int free, int reserved, int quarantined) {}

    private enum State {
        FREE,
        RESERVED,
        QUARANTINED
    }

    /** A port that changes state at {@code at} if it is still in the state that queued it. */
    private record Due(int offset, long at) {}

    private static final long NOT_HELD = 0;

    private final PortRange range;
    private final long reuseDelayMillis;
    private final long quarantineMillis;
    private final RandomGenerator random;
    private final InstantSource clock;

    private final State[] states;
    private final long[] holders;
    /** For a free port, when it was released; for a quarantined port, when its quarantine ends. */
    private final long[] stamps;

    private final OffsetSet cooled;
    private final OffsetSet warming;
    private final ArrayDeque<Due> warmingQueue = new ArrayDeque<>();
    private final ArrayDeque<Due> quarantineQueue = new ArrayDeque<>();

    private int reserved;
    private int quarantined;
    private long lastHolder = NOT_HELD;

    public PortPool(
            PortRange range, Duration reuseDelay, Duration quarantine, RandomGenerator random, InstantSource clock) {
        this.range = Objects.requireNonNull(range, "range");
        this.reuseDelayMillis = nonNegativeMillis(reuseDelay, "reuseDelay");
        this.quarantineMillis = nonNegativeMillis(quarantine, "quarantine");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");

        int size = range.size();
        this.states = new State[size];
        this.holders = new long[size];
        this.stamps = new long[size];
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

    /** Reserves {@code port} if it is in the range and free, however recently it was released. */
    public synchronized Optional<Held> reserveRequested(int port) {
        if (!range.contains(port)) {
            return Optional.empty();
        }
        refresh(clock.millis());
        int offset = port - range.first();
        if (states[offset] != State.FREE) {
            return Optional.empty();
        }
        return Optional.of(reserve(offset));
    }

    /**
     * Reserves a port chosen uniformly at random among those free for at least the reuse delay, or, when there are
     * none, among every free port. Empty when no port is free.
     */
    public synchronized Optional<Held> reserveAny() {
        refresh(clock.millis());
        OffsetSet from = cooled.size() > 0 ? cooled : warming;
        if (from.size() == 0) {
            return Optional.empty();
        }
        return Optional.of(reserve(from.get(random.nextInt(from.size()))));
    }

    /** Returns {@code port} to the pool if {@code holder} still holds it; otherwise does nothing. */
    public synchronized void release(int port, long holder) {
        long now = clock.millis();
        refresh(now);
        int offset = heldOffset(port, holder);
        if (offset < 0) {
            return;
        }
        unreserve(offset);
        free(offset, now);
    }

    /**
     * Holds {@code port} out of the pool for the quarantine period if {@code holder} still holds it; otherwise does
     * nothing. For a port whose bind failed because something else on the host uses it.
     */
    public synchronized void quarantine(int port, long holder) {
        long now = clock.millis();
        refresh(now);
        int offset = heldOffset(port, holder);
        if (offset < 0) {
            return;
        }
        unreserve(offset);
        long until = now + quarantineMillis;
        states[offset] = State.QUARANTINED;
        stamps[offset] = until;
        quarantined++;
        quarantineQueue.addLast(new Due(offset, until));
    }

    public synchronized Snapshot snapshot() {
        refresh(clock.millis());
        return new Snapshot(cooled.size() + warming.size(), reserved, quarantined);
    }

    private Held reserve(int offset) {
        if (cooled.contains(offset)) {
            cooled.remove(offset);
        } else {
            warming.remove(offset);
        }
        states[offset] = State.RESERVED;
        holders[offset] = ++lastHolder;
        reserved++;
        return new Held(range.first() + offset, lastHolder);
    }

    private int heldOffset(int port, long holder) {
        if (!range.contains(port)) {
            throw new IllegalArgumentException("Port " + port + " is outside the range " + range);
        }
        int offset = port - range.first();
        return states[offset] == State.RESERVED && holders[offset] == holder ? offset : -1;
    }

    private void unreserve(int offset) {
        holders[offset] = NOT_HELD;
        reserved--;
    }

    private void free(int offset, long releasedAt) {
        states[offset] = State.FREE;
        stamps[offset] = releasedAt;
        warming.add(offset);
        warmingQueue.addLast(new Due(offset, releasedAt));
    }

    /**
     * Ends every quarantine that is over, then moves every port free for the reuse delay into the cooled set. A queue
     * entry whose port has changed state since it was queued is stale and dropped.
     */
    private void refresh(long now) {
        while (!quarantineQueue.isEmpty() && quarantineQueue.peekFirst().at() <= now) {
            Due due = quarantineQueue.removeFirst();
            if (states[due.offset()] == State.QUARANTINED && stamps[due.offset()] == due.at()) {
                quarantined--;
                free(due.offset(), due.at());
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
