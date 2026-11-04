package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.application.port.LiveSessions;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.ResumeTokenHash;
import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every live session, by id, by the ids of its tunnels and by the hash of its resume token, across all event loops:
 * for the shutdown sweep, the edge that routes a request to the session serving its tunnel, step 2a of registration,
 * and, later, the admin API. A session is added when its pipeline is built and removed when its connection closes; its
 * tunnels and its token are added once it is established.
 */
public final class SessionRegistry implements LiveSessions {

    /**
     * The longest step 2a waits for the session it ended: the client's time to complete the close, and a second for
     * the listeners to close and the claims to go on hold.
     */
    public static final Duration REPLACE_TIMEOUT = SessionCloser.FORCE_CLOSE_TIMEOUT.plusSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private static final String REPLACED_MESSAGE =
            "A newer session of this client presented this session's resume token.";

    /** A session found by its token, with its owner as it was when it was established, readable from any thread. */
    private record Resumable(SessionContext context, Principal principal) {}

    private final ConcurrentMap<SessionId, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<TunnelId, SessionContext> byTunnel = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResumeTokenHash, Resumable> byResumeToken = new ConcurrentHashMap<>();

    void add(SessionContext session) {
        if (sessions.putIfAbsent(session.session().id(), session) != null) {
            throw new IllegalStateException("Session " + session.session().id() + " is already registered");
        }
    }

    /**
     * Makes the established session's tunnels findable, and the session itself by its resume token if it has one.
     * Called on the session's event loop.
     */
    void addTunnels(SessionContext session, List<Tunnel> tunnels) {
        for (Tunnel tunnel : tunnels) {
            if (byTunnel.putIfAbsent(tunnel.id(), session) != null) {
                throw new IllegalStateException("Tunnel " + tunnel.id() + " is already registered");
            }
        }
        Optional<ResumeTokenHash> token = session.session().resumeTokenHash();
        if (token.isPresent()) {
            Principal principal = session.session().principal().orElseThrow();
            if (byResumeToken.putIfAbsent(token.get(), new Resumable(session, principal)) != null) {
                throw new IllegalStateException("A resume token is already registered");
            }
        }
    }

    /**
     * Removes the session, its tunnels and its resume token. Called on the session's event loop, once its connection
     * has closed.
     */
    void remove(SessionContext session) {
        for (Tunnel tunnel : session.session().tunnels()) {
            byTunnel.remove(tunnel.id(), session);
        }
        session.session()
                .resumeTokenHash()
                .ifPresent(token -> byResumeToken.computeIfPresent(
                        token, (key, found) -> found.context() == session ? null : found));
        sessions.remove(session.session().id(), session);
    }

    /**
     * Takes the session out of the token index first, so a token works once however many registrations race with it,
     * then ends it on its own event loop and waits here, on the registration's thread, for its release. A wait that
     * times out goes on: a port still bound is refused to the new session as {@code PORT_UNAVAILABLE}, which its
     * client retries.
     */
    @Override
    public boolean replace(ResumeTokenHash tokenHash, Principal principal, SessionId replacement, Duration timeout) {
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(timeout, "timeout");
        Resumable found = byResumeToken.get(tokenHash);
        if (found == null
                || !found.principal().equals(principal)
                || found.context().session().id().equals(replacement)
                || !byResumeToken.remove(tokenHash, found)) {
            return false;
        }
        SessionContext stale = found.context();
        CompletableFuture<Void> released = stale.released();
        CompletableFuture<Boolean> ended = new CompletableFuture<>();
        try {
            stale.channel().eventLoop().execute(() -> ended.complete(end(stale, replacement)));
        } catch (RejectedExecutionException e) {
            return false;
        }
        await(released, stale, timeout);
        return ended.getNow(false);
    }

    /** On the stale session's loop: false if it was already ending on its own. */
    private static boolean end(SessionContext stale, SessionId replacement) {
        if (stale.session().state() == SessionState.CLOSING) {
            return false;
        }
        log.info(
                "session replaced: session={} replacedBy={} principal={} silentFor={}ms",
                stale.session().id(),
                replacement,
                stale.session().principal().map(Principal::name).orElse("-"),
                stale.liveness().silence().toMillis());
        stale.closer().fail(stale, ProtocolException.session(ErrorCode.SESSION_REPLACED, REPLACED_MESSAGE));
        return true;
    }

    private static void await(CompletableFuture<Void> released, SessionContext stale, Duration timeout) {
        try {
            released.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.warn(
                    "session {}: replaced, but its tunnels weren't released within {} ms",
                    stale.session().id(),
                    timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("A session's release never fails", e.getCause());
        }
    }

    /** A snapshot of the sessions live now. */
    public List<SessionContext> all() {
        return List.copyOf(sessions.values());
    }

    public Optional<SessionContext> find(SessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /** The session serving {@code tunnel}, from its creation until its session's connection has closed. */
    public Optional<SessionContext> find(TunnelId tunnel) {
        return Optional.ofNullable(byTunnel.get(tunnel));
    }

    public int active() {
        return sessions.size();
    }
}
