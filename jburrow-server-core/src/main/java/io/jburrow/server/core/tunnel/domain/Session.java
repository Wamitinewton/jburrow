package io.jburrow.server.core.tunnel.domain;

import static io.jburrow.protocol.wire.FrameType.CLOSE;
import static io.jburrow.protocol.wire.FrameType.DATA;
import static io.jburrow.protocol.wire.FrameType.ERROR;
import static io.jburrow.protocol.wire.FrameType.HELLO;
import static io.jburrow.protocol.wire.FrameType.PING;
import static io.jburrow.protocol.wire.FrameType.PONG;
import static io.jburrow.protocol.wire.FrameType.REGISTER;

import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One control session as the server sees it: its state ({@code docs/protocol.md} §8), its tunnels and its streams.
 * Every decision is a return value or a {@link ProtocolException}; the caller does the I/O.
 *
 * <p>Not thread-safe. A session is confined to the event loop its connection is registered on, for its whole life,
 * and is never touched from another thread.
 */
public final class Session {

    /** The frames a client may send in each state, {@code docs/protocol.md} §8.2. */
    private static final Map<SessionState, Set<FrameType>> ALLOWED_INBOUND = new EnumMap<>(SessionState.class);

    static {
        ALLOWED_INBOUND.put(SessionState.AWAITING_HELLO, EnumSet.of(HELLO, ERROR));
        ALLOWED_INBOUND.put(SessionState.AWAITING_REGISTER, EnumSet.of(REGISTER, PING, PONG, ERROR));
        ALLOWED_INBOUND.put(SessionState.REGISTERING, EnumSet.of(PING, PONG, ERROR));
        ALLOWED_INBOUND.put(SessionState.ESTABLISHED, EnumSet.of(DATA, CLOSE, PING, PONG, ERROR));
        ALLOWED_INBOUND.put(SessionState.CLOSING, EnumSet.allOf(FrameType.class));
    }

    private final SessionId id;
    private final StreamTable streams;

    private SessionState state = SessionState.AWAITING_HELLO;
    private ClientInfo client;
    private Principal principal;
    private Map<TunnelId, Tunnel> tunnels = Map.of();
    private ErrorCode closeReason;

    public Session(SessionId id, int maxConcurrentStreams) {
        this.id = Objects.requireNonNull(id, "id");
        this.streams = new StreamTable(maxConcurrentStreams);
    }

    public SessionId id() {
        return id;
    }

    public SessionState state() {
        return state;
    }

    /**
     * Checks that a frame of {@code type} from the client is allowed in the current state. In
     * {@link SessionState#CLOSING} every frame passes: the caller ignores it, releasing what it holds.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} for a frame out of order
     */
    public void checkInbound(FrameType type) {
        Objects.requireNonNull(type, "type");
        if (!ALLOWED_INBOUND.get(state).contains(type)) {
            throw outOfOrder(type);
        }
    }

    /** The client's {@code HELLO}: {@code AWAITING_HELLO -> AWAITING_REGISTER}. */
    public void onHello(Hello hello) {
        Objects.requireNonNull(hello, "hello");
        require(SessionState.AWAITING_HELLO, HELLO);
        client = hello.client();
        state = SessionState.AWAITING_REGISTER;
    }

    /** The client's {@code REGISTER}: {@code AWAITING_REGISTER -> REGISTERING}. A second one is out of order. */
    public void onRegisterReceived() {
        require(SessionState.AWAITING_REGISTER, REGISTER);
        state = SessionState.REGISTERING;
    }

    /**
     * Registration succeeded and its tunnels are bound: {@code REGISTERING -> ESTABLISHED}.
     *
     * @throws IllegalStateException if the session isn't registering; check {@link #isRegistering()} when the
     *     registration result comes back, since the session may have closed meanwhile
     */
    public void onEstablished(Principal principal, List<Tunnel> tunnels) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(tunnels, "tunnels");
        if (state != SessionState.REGISTERING) {
            throw new IllegalStateException("Session " + id + " can't be established while " + state);
        }
        if (tunnels.isEmpty()) {
            throw new IllegalArgumentException("An established session has at least one tunnel");
        }
        Map<TunnelId, Tunnel> byId = new LinkedHashMap<>();
        for (Tunnel tunnel : tunnels) {
            if (byId.put(tunnel.id(), tunnel) != null) {
                throw new IllegalArgumentException("Duplicate tunnel id " + tunnel.id());
            }
        }
        this.principal = principal;
        this.tunnels = byId;
        state = SessionState.ESTABLISHED;
    }

    public boolean isRegistering() {
        return state == SessionState.REGISTERING;
    }

    /**
     * Starts closing because of a session error, from any state. Only the first reason is kept; later calls do
     * nothing.
     */
    public void onClosing(ErrorCode reason) {
        Objects.requireNonNull(reason, "reason");
        if (!reason.scopes().contains(ErrorScope.SESSION)) {
            throw new IllegalArgumentException(reason + " is not a session error");
        }
        if (state != SessionState.CLOSING) {
            closeReason = reason;
            state = SessionState.CLOSING;
        }
    }

    /**
     * Starts closing with no protocol error: the client closed the connection, or it was lost. Does nothing if the
     * session is already closing.
     */
    public void onClosing() {
        state = SessionState.CLOSING;
    }

    /** The session error the session is closing for; empty if it is open or closed without one. */
    public Optional<ErrorCode> closeReason() {
        return Optional.ofNullable(closeReason);
    }

    /**
     * A visitor connected to {@code tunnel}. Admitted streams are open from this moment: the caller sends
     * {@code OPEN} on the returned id.
     *
     * @throws ProtocolException {@link ErrorCode#INTERNAL_ERROR} once every stream id is used
     */
    public StreamAdmission openStream(TunnelId tunnel) {
        Objects.requireNonNull(tunnel, "tunnel");
        if (state != SessionState.ESTABLISHED) {
            return new StreamAdmission.Refused(StreamAdmission.Refusal.NOT_ESTABLISHED);
        }
        if (!tunnels.containsKey(tunnel)) {
            throw new IllegalArgumentException("Tunnel " + tunnel + " is not part of session " + id);
        }
        if (streams.isFull()) {
            return new StreamAdmission.Refused(StreamAdmission.Refusal.STREAM_LIMIT);
        }
        return new StreamAdmission.Admitted(streams.allocate());
    }

    /**
     * A {@code DATA}, {@code CLOSE} or {@code CLOSE RST} from the client on {@code streamId}.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public StreamOutcome onStreamFrame(int streamId, StreamEvent received) {
        return streams.onReceived(streamId, Objects.requireNonNull(received, "received"));
    }

    /** Records what this side sent on {@code streamId}. A send the stream's state forbids is a bug in the caller. */
    public void onStreamSent(int streamId, StreamEvent sent) {
        streams.onSent(streamId, Objects.requireNonNull(sent, "sent"));
    }

    /** Every stream not yet closed, in increasing order: what teardown resets. */
    public List<Integer> openStreamIds() {
        return streams.openIds();
    }

    /**
     * Where stream {@code streamId} is, or empty once it has closed.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public Optional<StreamState> streamState(int streamId) {
        return streams.lookup(streamId).map(StreamLifecycle::state);
    }

    public Optional<Tunnel> tunnel(TunnelId id) {
        return Optional.ofNullable(tunnels.get(id));
    }

    /** The tunnels, in the order the client requested them; empty until established. */
    public List<Tunnel> tunnels() {
        return List.copyOf(tunnels.values());
    }

    /** The client's identity from its {@code HELLO}, once received. */
    public Optional<ClientInfo> client() {
        return Optional.ofNullable(client);
    }

    /** Who the session belongs to, once established. */
    public Optional<Principal> principal() {
        return Optional.ofNullable(principal);
    }

    private void require(SessionState expected, FrameType type) {
        if (state != expected) {
            throw outOfOrder(type);
        }
    }

    private ProtocolException outOfOrder(FrameType type) {
        return ProtocolException.session(
                ErrorCode.PROTOCOL_ERROR, type + " is not allowed while the session is " + describe(state) + ".");
    }

    private static String describe(SessionState state) {
        return switch (state) {
            case AWAITING_HELLO -> "awaiting HELLO";
            case AWAITING_REGISTER -> "awaiting REGISTER";
            case REGISTERING -> "registering";
            case ESTABLISHED -> "established";
            case CLOSING -> "closing";
        };
    }
}
