package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Optional;

/** Finds the session serving an HTTP tunnel, from any edge loop. Never blocks. */
@FunctionalInterface
public interface ExchangeSessions {

    /** The exchanges of the session serving {@code tunnel}; empty once it has ended, or for a tunnel with none. */
    Optional<SessionExchanges> find(TunnelId tunnel);

    /** No session serves any tunnel: every tunnel request gets the {@code TUNNEL_NOT_FOUND} page. */
    static ExchangeSessions none() {
        return tunnel -> Optional.empty();
    }
}
