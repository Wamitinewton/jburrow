package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** A {@link TunnelMetrics} that records each call as text, such as {@code "streamReset CANCELLED LOCAL"}. */
public final class RecordingTunnelMetrics implements TunnelMetrics {

    private final List<String> events = new CopyOnWriteArrayList<>();

    private volatile Supplier<PortPoolSnapshot> portPool;
    private volatile LongSupplier directMemory;
    private volatile LongSupplier parkedBytes;

    /** Every call so far, oldest first. */
    public List<String> events() {
        return List.copyOf(events);
    }

    public Supplier<PortPoolSnapshot> portPool() {
        return portPool;
    }

    public LongSupplier directMemory() {
        return directMemory;
    }

    @Override
    public void sessionStarted() {
        record("sessionStarted");
    }

    @Override
    public void sessionEnded(ErrorCode reason) {
        record("sessionEnded", reason);
    }

    @Override
    public void sessionEnded(SessionEnd reason) {
        record("sessionEnded", reason);
    }

    @Override
    public void handshakeEstablished() {
        record("handshakeEstablished");
    }

    @Override
    public void handshakeFailed(ErrorCode code) {
        record("handshakeFailed", code);
    }

    @Override
    public void registerSucceeded(long durationNanos) {
        record("registerSucceeded");
    }

    @Override
    public void registerFailed(long durationNanos, ErrorCode code) {
        record("registerFailed", code);
    }

    @Override
    public void streamOpened(TunnelKind kind) {
        record("streamOpened", kind);
    }

    @Override
    public void streamClosed(TunnelKind kind) {
        record("streamClosed", kind);
    }

    @Override
    public void streamReset(ErrorCode code, Side side) {
        record("streamReset", code, side);
    }

    @Override
    public void visitorRefused(StreamAdmission.Refusal reason) {
        record("visitorRefused", reason);
    }

    @Override
    public void tunnelBytes(TunnelKind kind, Direction direction, long bytes) {
        record("tunnelBytes", kind, direction, bytes);
    }

    @Override
    public void frame(FrameType type, FrameDirection direction) {
        record("frame", type, direction);
    }

    @Override
    public void protocolError(ErrorCode code, Side side) {
        record("protocolError", code, side);
    }

    @Override
    public void backpressurePaused(Direction direction) {
        record("backpressurePaused", direction);
    }

    @Override
    public void flowBlocked(FlowScope scope) {
        record("flowBlocked", scope);
    }

    @Override
    public void httpResponse(int status, ResponseOrigin origin) {
        record("httpResponse", status, origin);
    }

    @Override
    public void edgeErrorPage(String code) {
        record("edgeErrorPage", code);
    }

    @Override
    public void responseHead(long durationNanos) {
        record("responseHead");
    }

    @Override
    public void streamParked() {
        record("streamParked");
    }

    @Override
    public void parkRefused() {
        record("parkRefused");
    }

    @Override
    public void bindParkedBytes(LongSupplier parkedBytes) {
        this.parkedBytes = parkedBytes;
    }

    public LongSupplier parkedBytes() {
        return parkedBytes;
    }

    @Override
    public void bindPortPool(Supplier<PortPoolSnapshot> snapshot) {
        portPool = snapshot;
    }

    @Override
    public void bindDirectMemory(LongSupplier usedBytes) {
        directMemory = usedBytes;
    }

    private void record(String name, Object... arguments) {
        StringBuilder event = new StringBuilder(name);
        for (Object argument : arguments) {
            event.append(' ').append(argument);
        }
        events.add(event.toString());
    }
}
