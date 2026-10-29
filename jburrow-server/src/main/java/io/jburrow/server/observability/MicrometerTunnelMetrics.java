package io.jburrow.server.observability;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * {@link TunnelMetrics} on Micrometer, every metric of {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md}
 * §Observability.
 *
 * <p>Every label set is closed, so every series is registered up front: a dashboard sees a zero rather than a missing
 * series, and the event loops that report never touch the registry, only meters looked up in enum maps that are never
 * written after construction.
 */
public final class MicrometerTunnelMetrics implements TunnelMetrics {

    static final String ESTABLISHED = "established";

    private final MeterRegistry registry;

    private final AtomicInteger sessionsActive = new AtomicInteger();
    private final Counter handshakesEstablished;
    private final Map<ErrorCode, Counter> handshakesFailed;
    private final Map<ErrorCode, Counter> sessionsClosedByCode;
    private final Map<SessionEnd, Counter> sessionsClosedByEnd;
    private final Timer registerSucceeded;
    private final Map<ErrorCode, Timer> registerFailed;
    private final Map<TunnelKind, AtomicInteger> streamsActive = new EnumMap<>(TunnelKind.class);
    private final Map<TunnelKind, Counter> streamsOpened;
    private final Map<Side, Map<ErrorCode, Counter>> streamResets = new EnumMap<>(Side.class);
    private final Map<StreamAdmission.Refusal, Counter> visitorsRefused;
    private final Map<TunnelKind, Map<Direction, Counter>> tunnelBytes = new EnumMap<>(TunnelKind.class);
    private final Map<FrameType, Map<FrameDirection, Counter>> frames = new EnumMap<>(FrameType.class);
    private final Map<Side, Map<ErrorCode, Counter>> protocolErrors = new EnumMap<>(Side.class);
    private final Map<Direction, Counter> backpressurePauses;
    private final Map<FlowScope, Counter> flowBlocked;

    public MicrometerTunnelMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");

        Gauge.builder("jburrow.sessions.active", sessionsActive, AtomicInteger::get)
                .description("Sessions whose connection has upgraded and not yet closed")
                .register(registry);

        handshakesEstablished = handshakes(ESTABLISHED);
        handshakesFailed = byCode(codes(ErrorScope.UPGRADE, ErrorScope.SESSION), code -> handshakes(code.code()));

        sessionsClosedByCode = byCode(codes(ErrorScope.SESSION), code -> sessionsClosed(code.code()));
        sessionsClosedByEnd = byEnum(SessionEnd.class, end -> sessionsClosed(label(end)));

        registerSucceeded = registerTimer(ESTABLISHED);
        registerFailed = byCode(codes(ErrorScope.SESSION), code -> registerTimer(code.code()));

        streamsOpened = byEnum(
                TunnelKind.class,
                kind -> Counter.builder("jburrow.streams.opened")
                        .description("Streams opened")
                        .tag("kind", kind.wireName())
                        .register(registry));
        for (TunnelKind kind : TunnelKind.values()) {
            AtomicInteger active = new AtomicInteger();
            streamsActive.put(kind, active);
            Gauge.builder("jburrow.streams.active", active, AtomicInteger::get)
                    .description("Streams open now")
                    .tag("kind", kind.wireName())
                    .register(registry);
        }

        for (Side side : Side.values()) {
            streamResets.put(
                    side,
                    byCode(
                            codes(ErrorScope.STREAM),
                            code -> Counter.builder("jburrow.stream.resets")
                                    .description("Streams ended by a reset")
                                    .tag("code", code.code())
                                    .tag("side", label(side))
                                    .register(registry)));
            protocolErrors.put(
                    side,
                    byCode(
                            codes(ErrorScope.SESSION, ErrorScope.STREAM),
                            code -> Counter.builder("jburrow.protocol.errors")
                                    .description("Protocol errors raised by this server or its peer")
                                    .tag("code", code.code())
                                    .tag("side", label(side))
                                    .register(registry)));
        }

        visitorsRefused = byEnum(
                StreamAdmission.Refusal.class,
                reason -> Counter.builder("jburrow.visitor.connections.refused")
                        .description("Visitor connections closed without a stream")
                        .tag("reason", reason.label())
                        .register(registry));

        for (TunnelKind kind : TunnelKind.values()) {
            tunnelBytes.put(
                    kind,
                    byEnum(
                            Direction.class,
                            direction -> Counter.builder("jburrow.tunnel.bytes")
                                    .description("Payload bytes relayed through tunnels; inbound is visitor to client")
                                    .baseUnit("bytes")
                                    .tag("kind", kind.wireName())
                                    .tag("direction", label(direction))
                                    .register(registry)));
        }
        for (FrameType type : FrameType.values()) {
            frames.put(
                    type,
                    byEnum(
                            FrameDirection.class,
                            direction -> Counter.builder("jburrow.frames")
                                    .description("Frames received from and sent to clients")
                                    .tag("type", label(type))
                                    .tag("direction", label(direction))
                                    .register(registry)));
        }
        backpressurePauses = byEnum(
                Direction.class,
                direction -> Counter.builder("jburrow.backpressure.pauses")
                        .description("Times reading one side paused because the other couldn't keep up")
                        .tag("direction", label(direction))
                        .register(registry));
        flowBlocked = byEnum(
                FlowScope.class,
                scope -> Counter.builder("jburrow.flow.blocked")
                        .description(
                                "Times a visitor stopped being read because its stream's or session's credit ran out")
                        .tag("scope", label(scope))
                        .register(registry));
    }

    @Override
    public void sessionStarted() {
        sessionsActive.incrementAndGet();
    }

    @Override
    public void sessionEnded(ErrorCode reason) {
        sessionsActive.decrementAndGet();
        meter(sessionsClosedByCode, reason).increment();
    }

    @Override
    public void sessionEnded(SessionEnd reason) {
        sessionsActive.decrementAndGet();
        sessionsClosedByEnd.get(reason).increment();
    }

    @Override
    public void handshakeEstablished() {
        handshakesEstablished.increment();
    }

    @Override
    public void handshakeFailed(ErrorCode code) {
        meter(handshakesFailed, code).increment();
    }

    @Override
    public void registerSucceeded(long durationNanos) {
        registerSucceeded.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void registerFailed(long durationNanos, ErrorCode code) {
        meter(registerFailed, code).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void streamOpened(TunnelKind kind) {
        streamsOpened.get(kind).increment();
        streamsActive.get(kind).incrementAndGet();
    }

    @Override
    public void streamClosed(TunnelKind kind) {
        streamsActive.get(kind).decrementAndGet();
    }

    @Override
    public void streamReset(ErrorCode code, Side side) {
        meter(streamResets.get(side), code).increment();
    }

    @Override
    public void visitorRefused(StreamAdmission.Refusal reason) {
        visitorsRefused.get(reason).increment();
    }

    @Override
    public void tunnelBytes(TunnelKind kind, Direction direction, long bytes) {
        tunnelBytes.get(kind).get(direction).increment(bytes);
    }

    @Override
    public void frame(FrameType type, FrameDirection direction) {
        frames.get(type).get(direction).increment();
    }

    @Override
    public void protocolError(ErrorCode code, Side side) {
        meter(protocolErrors.get(side), code).increment();
    }

    @Override
    public void backpressurePaused(Direction direction) {
        backpressurePauses.get(direction).increment();
    }

    @Override
    public void flowBlocked(FlowScope scope) {
        flowBlocked.get(scope).increment();
    }

    @Override
    public void bindPortPool(Supplier<PortPoolSnapshot> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        portGauge("free", snapshot, PortPoolSnapshot::free);
        portGauge("reserved", snapshot, PortPoolSnapshot::reserved);
        portGauge("quarantined", snapshot, PortPoolSnapshot::quarantined);
    }

    @Override
    public void bindDirectMemory(LongSupplier usedBytes) {
        Objects.requireNonNull(usedBytes, "usedBytes");
        Gauge.builder("jburrow.netty.direct.memory", usedBytes::getAsLong)
                .description("Direct memory held by Netty's buffers")
                .baseUnit("bytes")
                .register(registry);
    }

    private void portGauge(String state, Supplier<PortPoolSnapshot> snapshot, ToIntFunction<PortPoolSnapshot> count) {
        Gauge.builder("jburrow.tcp.ports", () -> count.applyAsInt(snapshot.get()))
                .description("Tunnel ports by state")
                .tag("state", state)
                .register(registry);
    }

    private Counter handshakes(String result) {
        return Counter.builder("jburrow.session.handshakes")
                .description("Connections that reached an established session, or the code that ended them first")
                .tag("result", result)
                .register(registry);
    }

    private Counter sessionsClosed(String reason) {
        return Counter.builder("jburrow.sessions.closed")
                .description("Sessions closed, by reason")
                .tag("reason", reason)
                .register(registry);
    }

    private Timer registerTimer(String result) {
        return Timer.builder("jburrow.register.duration")
                .description("Time from a REGISTER to its reply")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry);
    }

    /** The meter for {@code code}; a code outside the label set is a bug in the caller, not a new series. */
    private static <M> M meter(Map<ErrorCode, M> meters, ErrorCode code) {
        M meter = meters.get(Objects.requireNonNull(code, "code"));
        if (meter == null) {
            throw new IllegalArgumentException(code + " is not a label of this metric");
        }
        return meter;
    }

    private static ErrorCode[] codes(ErrorScope... scopes) {
        return Arrays.stream(ErrorCode.values())
                .filter(code -> Arrays.stream(scopes).anyMatch(code.scopes()::contains))
                .toArray(ErrorCode[]::new);
    }

    private static <M> Map<ErrorCode, M> byCode(ErrorCode[] codes, Function<ErrorCode, M> meter) {
        Map<ErrorCode, M> meters = new EnumMap<>(ErrorCode.class);
        for (ErrorCode code : codes) {
            meters.put(code, meter.apply(code));
        }
        return meters;
    }

    private static <E extends Enum<E>, M> Map<E, M> byEnum(Class<E> type, Function<E, M> meter) {
        Map<E, M> meters = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            meters.put(value, meter.apply(value));
        }
        return meters;
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
