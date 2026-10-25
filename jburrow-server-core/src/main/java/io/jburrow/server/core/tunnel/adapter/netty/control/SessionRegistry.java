package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.server.core.tunnel.domain.SessionId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Every live session, by id, across all event loops: for the shutdown sweep and, later, the admin API. A session is
 * added when its pipeline is built and removed when its connection closes.
 */
public final class SessionRegistry {

    private final ConcurrentMap<SessionId, SessionContext> sessions = new ConcurrentHashMap<>();

    void add(SessionContext session) {
        if (sessions.putIfAbsent(session.session().id(), session) != null) {
            throw new IllegalStateException("Session " + session.session().id() + " is already registered");
        }
    }

    void remove(SessionContext session) {
        sessions.remove(session.session().id(), session);
    }

    /** A snapshot of the sessions live now. */
    public List<SessionContext> all() {
        return List.copyOf(sessions.values());
    }

    public Optional<SessionContext> find(SessionId id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public int active() {
        return sessions.size();
    }
}
