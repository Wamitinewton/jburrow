package io.jburrow.server.core.tunnel.application;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many stalled response bytes the server may park ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Paused
 * readers): at most {@code maxParked} across every session, and {@code maxParkedPerSession} in any one. Parked bytes
 * still sit in direct memory, so this is the memory bound parking adds.
 *
 * <p>Thread-safe: the server total is shared by every event loop. Each session takes and gives back through its own
 * {@link Account}, which is confined to the session's event loop. Every byte taken is given back exactly once.
 */
public final class ParkingBudget {

    private final long maxParked;
    private final long maxParkedPerSession;
    private final AtomicLong parked = new AtomicLong();

    public ParkingBudget(long maxParked, long maxParkedPerSession) {
        if (maxParked < 0 || maxParkedPerSession < 0 || maxParkedPerSession > maxParked) {
            throw new IllegalArgumentException("Parking limits must satisfy 0 <= maxParkedPerSession <= maxParked: "
                    + maxParkedPerSession + ", " + maxParked);
        }
        this.maxParked = maxParked;
        this.maxParkedPerSession = maxParkedPerSession;
    }

    /** Whether anything may be parked at all: {@code max-parked} 0 turns parking off. */
    public boolean enabled() {
        return maxParkedPerSession > 0;
    }

    /** A new session's share of the budget, empty. */
    public Account account() {
        return new Account();
    }

    /** Bytes parked across the server now. */
    public long parked() {
        return parked.get();
    }

    public long maxParked() {
        return maxParked;
    }

    /** One session's parked bytes. Not thread-safe: confined to the session's event loop. */
    public final class Account {

        private long held;

        private Account() {}

        /** Takes {@code bytes} if both this session and the server stay within their limits; false changes nothing. */
        public boolean tryTake(long bytes) {
            if (bytes < 1) {
                throw new IllegalArgumentException("Parking takes at least 1 byte, not " + bytes);
            }
            if (held + bytes > maxParkedPerSession) {
                return false;
            }
            while (true) {
                long total = parked.get();
                if (total + bytes > maxParked) {
                    return false;
                }
                if (parked.compareAndSet(total, total + bytes)) {
                    held += bytes;
                    return true;
                }
            }
        }

        /**
         * Gives back {@code bytes} that are no longer parked. 0 changes nothing.
         *
         * @throws IllegalStateException if more is given back than this session took
         */
        public void giveBack(long bytes) {
            if (bytes < 0 || bytes > held) {
                throw new IllegalStateException(
                        "Giving back " + bytes + " parked bytes, of the " + held + " this session holds");
            }
            held -= bytes;
            parked.addAndGet(-bytes);
        }

        /** Bytes this session has parked now. */
        public long held() {
            return held;
        }
    }
}
