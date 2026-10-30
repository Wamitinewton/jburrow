package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.server.core.tunnel.application.PlannedTunnel;
import io.jburrow.server.core.tunnel.application.Registration;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.netty.util.concurrent.Future;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tunnel listeners that bind nothing: every planned tunnel is reported public on the terms of {@code policy}.
 * {@link #failBindsWith} makes every bind fail instead.
 */
final class FakeTunnelListeners implements TunnelListeners {

    private final RegistrationPolicy policy;
    private final AtomicInteger binds = new AtomicInteger();
    private final AtomicInteger unbinds = new AtomicInteger();
    private volatile Throwable bindFailure;

    FakeTunnelListeners(RegistrationPolicy policy) {
        this.policy = policy;
    }

    void failBindsWith(Throwable failure) {
        this.bindFailure = failure;
    }

    @Override
    public Future<List<Tunnel>> bind(SessionContext session, Registration registration) {
        binds.incrementAndGet();
        Throwable failure = bindFailure;
        if (failure != null) {
            return session.channel().eventLoop().newFailedFuture(failure);
        }
        List<Tunnel> tunnels = registration.tunnels().stream()
                .map((PlannedTunnel planned) -> planned.toTunnel(policy))
                .toList();
        return session.channel().eventLoop().newSucceededFuture(tunnels);
    }

    @Override
    public Future<?> unbindAll(SessionContext session) {
        unbinds.incrementAndGet();
        return session.channel().eventLoop().newSucceededFuture(null);
    }

    int binds() {
        return binds.get();
    }

    int unbinds() {
        return unbinds.get();
    }
}
