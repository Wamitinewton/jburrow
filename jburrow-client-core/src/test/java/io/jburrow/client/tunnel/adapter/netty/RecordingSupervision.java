package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.GoAway;
import io.jburrow.protocol.control.RegisteredTunnel;
import java.util.List;
import java.util.Optional;

/** Keeps what a {@link ControlConnection} reports, for tests that drive one without a supervisor. */
final class RecordingSupervision implements ControlConnection.Supervision {

    private TunnelStartException startFailure;
    private TunnelClosed ended;

    @Override
    public void onEstablished(ControlConnection connection, List<RegisteredTunnel> tunnels) {}

    @Override
    public void onStartFailed(ControlConnection connection, TunnelStartException failure) {
        if (startFailure != null || ended != null) {
            throw new AssertionError("A connection reported its end twice");
        }
        startFailure = failure;
    }

    @Override
    public void onEnded(ControlConnection connection, TunnelClosed closed, Optional<GoAway> goAway) {
        if (startFailure != null || ended != null) {
            throw new AssertionError("A connection reported its end twice");
        }
        ended = closed;
    }

    /** The reported start failure. */
    TunnelStartException startFailure() {
        if (startFailure == null) {
            throw new AssertionError("No start failure was reported");
        }
        return startFailure;
    }
}
