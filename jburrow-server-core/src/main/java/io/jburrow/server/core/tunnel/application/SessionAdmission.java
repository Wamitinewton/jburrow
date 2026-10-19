package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.application.HandshakeTicket.Admitted;
import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The server-wide session caps: sessions in total, connections still handshaking, and sessions per principal. The
 * only state in the {@code tunnel} feature shared across event loops, so it is thread-safe.
 *
 * <p>A connection takes a {@link HandshakeTicket} when it upgrades, which counts against both the session and the
 * handshake caps. {@link #admit} moves it to its principal, freeing the handshake slot. {@link #release} frees
 * whatever the ticket holds, from any thread, as often as it is called.
 */
public final class SessionAdmission {

    /** Sessions in the high 32 bits, handshaking connections in the low 32, so both caps are checked in one CAS. */
    private static final long ONE_SESSION = 1L << 32;

    private static final long ONE_HANDSHAKE = 1L;
    private static final long LOW_MASK = 0xFFFF_FFFFL;

    private final int maxSessions;
    private final int maxHandshaking;
    private final int maxSessionsPerPrincipal;

    private final AtomicLong counts = new AtomicLong();
    private final ConcurrentMap<Principal, Integer> perPrincipal = new ConcurrentHashMap<>();

    public SessionAdmission(int maxSessions, int maxHandshaking, int maxSessionsPerPrincipal) {
        if (maxSessions < 1 || maxHandshaking < 1 || maxSessionsPerPrincipal < 1) {
            throw new IllegalArgumentException("Session caps must be positive");
        }
        this.maxSessions = maxSessions;
        this.maxHandshaking = maxHandshaking;
        this.maxSessionsPerPrincipal = maxSessionsPerPrincipal;
    }

    /** A ticket for a new connection, or empty if the server is at either cap. */
    public Optional<HandshakeTicket> tryBeginHandshake() {
        while (true) {
            long current = counts.get();
            if (sessions(current) >= maxSessions || handshaking(current) >= maxHandshaking) {
                return Optional.empty();
            }
            if (counts.compareAndSet(current, current + ONE_SESSION + ONE_HANDSHAKE)) {
                return Optional.of(new HandshakeTicket(this));
            }
        }
    }

    /**
     * Counts the ticket's session against {@code principal}. Does nothing if the ticket was released meanwhile: its
     * connection is gone, and the caller discards the registration when it finds the session closed.
     *
     * @throws ProtocolException {@link ErrorCode#SESSION_LIMIT_EXCEEDED} if the principal is at its cap
     * @throws IllegalStateException if the ticket was already admitted
     */
    public void admit(HandshakeTicket ticket, Principal principal) {
        requireOwn(ticket);
        Objects.requireNonNull(principal, "principal");
        if (ticket.stage() != HandshakeTicket.HANDSHAKING) {
            requireNotAdmitted(ticket);
            return;
        }
        perPrincipal.compute(principal, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= maxSessionsPerPrincipal) {
                throw ProtocolException.session(
                        ErrorCode.SESSION_LIMIT_EXCEEDED,
                        "The authtoken's owner already has " + maxSessionsPerPrincipal + " sessions.");
            }
            return current + 1;
        });
        if (ticket.admit(principal)) {
            counts.addAndGet(-ONE_HANDSHAKE);
            return;
        }
        decrement(principal);
        requireNotAdmitted(ticket);
    }

    /** Frees whatever the ticket holds. Safe to call more than once and from any thread. */
    public void release(HandshakeTicket ticket) {
        requireOwn(ticket);
        HandshakeTicket.Stage previous = ticket.release();
        if (previous instanceof HandshakeTicket.Handshaking) {
            counts.addAndGet(-(ONE_SESSION + ONE_HANDSHAKE));
        } else if (previous instanceof Admitted admitted) {
            decrement(admitted.principal());
            counts.addAndGet(-ONE_SESSION);
        }
    }

    /** Connections holding a ticket, handshaking or admitted. */
    public int activeSessions() {
        return sessions(counts.get());
    }

    public int handshaking() {
        return handshaking(counts.get());
    }

    public int sessionsOf(Principal principal) {
        return perPrincipal.getOrDefault(principal, 0);
    }

    private void decrement(Principal principal) {
        perPrincipal.computeIfPresent(principal, (key, count) -> count > 1 ? count - 1 : null);
    }

    private void requireOwn(HandshakeTicket ticket) {
        if (Objects.requireNonNull(ticket, "ticket").owner() != this) {
            throw new IllegalArgumentException("The ticket belongs to another SessionAdmission");
        }
    }

    private static void requireNotAdmitted(HandshakeTicket ticket) {
        if (ticket.stage() instanceof Admitted) {
            throw new IllegalStateException("The ticket was already admitted");
        }
    }

    private static int sessions(long counts) {
        return (int) (counts >>> 32);
    }

    private static int handshaking(long counts) {
        return (int) (counts & LOW_MASK);
    }
}
