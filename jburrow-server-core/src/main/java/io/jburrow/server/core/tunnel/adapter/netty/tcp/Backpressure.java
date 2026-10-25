package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Direction;
import io.netty.channel.Channel;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Writability backpressure between one session's connection and its visitors,
 * {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Backpressure in phase 1:
 *
 * <ul>
 *   <li>the session's connection can't take more: stop reading every visitor, until it drains;
 *   <li>a visitor can't take more: stop reading the session's connection and pause its liveness clock, until every
 *       visitor that stopped it has drained or gone.
 * </ul>
 *
 * <p>TCP's own flow control then carries the pressure to whoever is sending, so no queue grows on the server.
 * Confined to the session's event loop, so pausing is a direct {@code setAutoRead} with no race.
 */
final class Backpressure {

    private final SessionContext context;
    private final Channel session;
    private final TunnelMetrics metrics;
    /** The visitors whose full write buffers keep the session's connection from being read. */
    private final Set<VisitorHandler> pausing = new LinkedHashSet<>();

    Backpressure(SessionContext context, TunnelMetrics metrics) {
        this.context = Objects.requireNonNull(context, "context");
        this.session = context.channel();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** A visitor's stream was opened: read it unless the session's connection is already full. */
    void onOpened(VisitorHandler visitor) {
        visitor.channel().config().setAutoRead(session.isWritable());
    }

    void onSessionWritabilityChanged(boolean writable, Collection<VisitorHandler> visitors) {
        if (!writable) {
            if (visitors.isEmpty()) {
                return;
            }
            for (VisitorHandler visitor : visitors) {
                visitor.channel().config().setAutoRead(false);
            }
            metrics.backpressurePaused(Direction.INBOUND);
            return;
        }
        for (VisitorHandler visitor : visitors) {
            if (!visitor.channel().isInputShutdown()) {
                visitor.channel().config().setAutoRead(true);
            }
        }
    }

    void onVisitorWritabilityChanged(VisitorHandler visitor, boolean writable) {
        if (!writable) {
            if (pausing.add(visitor) && pausing.size() == 1) {
                session.config().setAutoRead(false);
                context.liveness().pause();
                metrics.backpressurePaused(Direction.OUTBOUND);
            }
        } else if (pausing.remove(visitor) && pausing.isEmpty()) {
            resumeSession();
        }
    }

    /** A visitor that stopped the session's connection is gone, so it no longer holds it. */
    void onRemoved(VisitorHandler visitor) {
        if (pausing.remove(visitor) && pausing.isEmpty()) {
            resumeSession();
        }
    }

    /** Whether the session's connection is paused for a visitor. */
    boolean isSessionPaused() {
        return !pausing.isEmpty();
    }

    private void resumeSession() {
        if (session.isActive()) {
            session.config().setAutoRead(true);
            context.liveness().resume();
        }
    }
}
