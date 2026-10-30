package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.server.core.tunnel.domain.SessionId;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Every live session, by id and by the ids of its tunnels, across all event loops: for the shutdown sweep, the edge
 * that routes a request to the session serving its tunnel, and, later, the admin API. A session is added when its
 * pipeline is built and removed when its connection closes; its tunnels are added once they are created.
 */
public final class SessionRegistry {

    private final ConcurrentMap<SessionId, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<TunnelId, SessionContext> byTunnel = new ConcurrentHashMap<>();

    void add(SessionContext session) {
        if (sessions.putIfAbsent(session.session().id(), session) != null) {
            throw new IllegalStateException("Session " + session.session().id() + " is already registered");
        }
    }

    /** Makes the session's tunnels findable, once they exist. Called on the session's event loop. */
    void addTunnels(SessionContext session, List<Tunnel> tunnels) {
        for (Tunnel tunnel : tunnels) {
            if (byTunnel.putIfAbsent(tunnel.id(), session) != null) {
                throw new IllegalStateException("Tunnel " + tunnel.id() + " is already registered");
            }
        }
    }

    /** Removes the session and its tunnels. Called on the session's event loop, once its connection has closed. */
    void remove(SessionContext session) {
        for (Tunnel tunnel : session.session().tunnels()) {
            byTunnel.remove(tunnel.id(), session);
        }
        sessions.remove(session.session().id(), session);
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
