package io.jburrow.server.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Direction;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.FlowScope;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.FrameDirection;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.SessionEnd;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Side;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MicrometerTunnelMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerTunnelMetrics metrics = new MicrometerTunnelMetrics(registry);

    @Test
    void registersEveryClosedSeriesUpFront() {
        assertThat(counter("jburrow.session.handshakes", "result", "established"))
                .isZero();
        assertThat(counter("jburrow.session.handshakes", "result", "SERVER_AT_CAPACITY"))
                .isZero();
        assertThat(counter("jburrow.session.handshakes", "result", "AUTHTOKEN_INVALID"))
                .isZero();
        assertThat(counter("jburrow.sessions.closed", "reason", "client_closed"))
                .isZero();
        assertThat(counter("jburrow.sessions.closed", "reason", "HEARTBEAT_TIMEOUT"))
                .isZero();
        assertThat(registry.get("jburrow.register.duration")
                        .tag("result", "established")
                        .timer())
                .isNotNull();
        assertThat(registry.get("jburrow.frames").counters()).hasSize(FrameType.values().length * 2);
        assertThat(registry.get("jburrow.stream.resets")
                        .tag("code", "CANCELLED")
                        .tag("side", "local")
                        .counter()
                        .count())
                .isZero();
        assertThat(registry.get("jburrow.visitor.connections.refused")
                        .tag("reason", "stream_limit")
                        .counter()
                        .count())
                .isZero();
        assertThat(gauge("jburrow.sessions.active")).isZero();
    }

    @Test
    void noSeriesCarriesAnIdentifyingLabel() {
        Set<String> keys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());

        assertThat(keys).isSubsetOf("result", "reason", "kind", "code", "side", "type", "direction", "state", "scope");
    }

    @Test
    void countsSessionsHandshakesAndRegistrations() {
        metrics.sessionStarted();
        metrics.sessionStarted();
        metrics.sessionEnded(ErrorCode.HEARTBEAT_TIMEOUT);
        metrics.handshakeEstablished();
        metrics.handshakeFailed(ErrorCode.FORBIDDEN_ORIGIN);
        metrics.registerSucceeded(Duration.ofMillis(5).toNanos());
        metrics.registerFailed(Duration.ofMillis(2).toNanos(), ErrorCode.AUTHTOKEN_INVALID);

        assertThat(gauge("jburrow.sessions.active")).isOne();
        assertThat(counter("jburrow.sessions.closed", "reason", "HEARTBEAT_TIMEOUT"))
                .isOne();
        assertThat(counter("jburrow.session.handshakes", "result", "established"))
                .isOne();
        assertThat(counter("jburrow.session.handshakes", "result", "FORBIDDEN_ORIGIN"))
                .isOne();
        assertThat(registry.get("jburrow.register.duration")
                        .tag("result", "AUTHTOKEN_INVALID")
                        .timer()
                        .count())
                .isOne();

        metrics.sessionEnded(SessionEnd.CONNECTION_LOST);
        assertThat(gauge("jburrow.sessions.active")).isZero();
        assertThat(counter("jburrow.sessions.closed", "reason", "connection_lost"))
                .isOne();
    }

    @Test
    void countsStreamsBytesFramesAndPauses() {
        metrics.streamOpened(TunnelKind.TCP);
        metrics.streamReset(ErrorCode.PEER_RESET, Side.REMOTE);
        metrics.tunnelBytes(TunnelKind.TCP, Direction.INBOUND, 1_500);
        metrics.frame(FrameType.DATA, FrameDirection.SENT);
        metrics.protocolError(ErrorCode.FRAME_SIZE_ERROR, Side.REMOTE);
        metrics.backpressurePaused(Direction.OUTBOUND);
        metrics.flowBlocked(FlowScope.SESSION);
        metrics.visitorRefused(StreamAdmission.Refusal.STREAM_LIMIT);

        assertThat(gauge("jburrow.streams.active", "kind", "tcp")).isOne();
        assertThat(counter("jburrow.streams.opened", "kind", "tcp")).isOne();
        assertThat(registry.get("jburrow.stream.resets")
                        .tag("code", "PEER_RESET")
                        .tag("side", "remote")
                        .counter()
                        .count())
                .isOne();
        assertThat(registry.get("jburrow.tunnel.bytes")
                        .tag("direction", "inbound")
                        .counter()
                        .count())
                .isEqualTo(1_500);
        assertThat(registry.get("jburrow.frames")
                        .tag("type", "data")
                        .tag("direction", "sent")
                        .counter()
                        .count())
                .isOne();
        assertThat(registry.get("jburrow.protocol.errors")
                        .tag("code", "FRAME_SIZE_ERROR")
                        .tag("side", "remote")
                        .counter()
                        .count())
                .isOne();
        assertThat(counter("jburrow.backpressure.pauses", "direction", "outbound"))
                .isOne();
        assertThat(counter("jburrow.flow.blocked", "scope", "session")).isOne();
        assertThat(counter("jburrow.flow.blocked", "scope", "stream")).isZero();
        assertThat(counter("jburrow.visitor.connections.refused", "reason", "stream_limit"))
                .isOne();

        metrics.streamClosed(TunnelKind.TCP);
        assertThat(gauge("jburrow.streams.active", "kind", "tcp")).isZero();
    }

    @Test
    void registersAndCountsTheFlowControlSeries() {
        assertThat(counter("jburrow.flow.blocked", "scope", "stream")).isZero();
        assertThat(counter("jburrow.flow.blocked", "scope", "session")).isZero();
        assertThat(counter("jburrow.visitor.connections.refused", "reason", "server_stream_limit"))
                .isZero();
        assertThat(registry.get("jburrow.protocol.errors")
                        .tag("code", "FLOW_CONTROL_ERROR")
                        .counters())
                .hasSize(Side.values().length);

        metrics.flowBlocked(FlowScope.STREAM);
        metrics.flowBlocked(FlowScope.STREAM);
        metrics.visitorRefused(StreamAdmission.Refusal.SERVER_STREAM_LIMIT);
        metrics.protocolError(ErrorCode.FLOW_CONTROL_ERROR, Side.LOCAL);
        metrics.frame(FrameType.WINDOW_UPDATE, FrameDirection.RECEIVED);

        assertThat(counter("jburrow.flow.blocked", "scope", "stream")).isEqualTo(2);
        assertThat(counter("jburrow.visitor.connections.refused", "reason", "server_stream_limit"))
                .isOne();
        assertThat(registry.get("jburrow.protocol.errors")
                        .tag("code", "FLOW_CONTROL_ERROR")
                        .tag("side", "local")
                        .counter()
                        .count())
                .isOne();
        assertThat(registry.get("jburrow.frames")
                        .tag("type", "window_update")
                        .tag("direction", "received")
                        .counter()
                        .count())
                .isOne();
        assertThat(registry.get("jburrow.frames")
                        .tag("type", "window_update")
                        .tag("direction", "sent")
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    void gaugesReadThePortPoolAndDirectMemory() {
        metrics.bindPortPool(() -> new PortPoolSnapshot(90, 7, 3));
        metrics.bindDirectMemory(() -> 4_096);

        assertThat(gauge("jburrow.tcp.ports", "state", "free")).isEqualTo(90);
        assertThat(gauge("jburrow.tcp.ports", "state", "reserved")).isEqualTo(7);
        assertThat(gauge("jburrow.tcp.ports", "state", "quarantined")).isEqualTo(3);
        assertThat(gauge("jburrow.netty.direct.memory")).isEqualTo(4_096);
    }

    @Test
    void aCodeOutsideTheLabelSetIsACallerBug() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.streamReset(ErrorCode.AUTHTOKEN_INVALID, Side.LOCAL));
    }

    private double counter(String name, String key, String value) {
        return registry.get(name).tag(key, value).counter().count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }
}
