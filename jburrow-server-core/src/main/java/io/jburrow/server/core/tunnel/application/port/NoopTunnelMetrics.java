package io.jburrow.server.core.tunnel.application.port;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

enum NoopTunnelMetrics implements TunnelMetrics {
    INSTANCE;

    @Override
    public void sessionStarted() {}

    @Override
    public void sessionEnded(ErrorCode reason) {}

    @Override
    public void sessionEnded(SessionEnd reason) {}

    @Override
    public void handshakeEstablished() {}

    @Override
    public void handshakeFailed(ErrorCode code) {}

    @Override
    public void registerSucceeded(long durationNanos) {}

    @Override
    public void registerFailed(long durationNanos, ErrorCode code) {}

    @Override
    public void streamOpened(TunnelKind kind) {}

    @Override
    public void streamClosed(TunnelKind kind) {}

    @Override
    public void streamReset(ErrorCode code, Side side) {}

    @Override
    public void visitorRefused(StreamAdmission.Refusal reason) {}

    @Override
    public void tunnelBytes(TunnelKind kind, Direction direction, long bytes) {}

    @Override
    public void frame(FrameType type, FrameDirection direction) {}

    @Override
    public void protocolError(ErrorCode code, Side side) {}

    @Override
    public void backpressurePaused(Direction direction) {}

    @Override
    public void bindPortPool(Supplier<PortPoolSnapshot> snapshot) {}

    @Override
    public void bindDirectMemory(LongSupplier usedBytes) {}
}
