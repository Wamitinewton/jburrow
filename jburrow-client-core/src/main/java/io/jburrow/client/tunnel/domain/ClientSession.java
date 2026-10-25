package io.jburrow.client.tunnel.domain;

import static io.jburrow.protocol.wire.FrameType.CLOSE;
import static io.jburrow.protocol.wire.FrameType.DATA;
import static io.jburrow.protocol.wire.FrameType.ERROR;
import static io.jburrow.protocol.wire.FrameType.HELLO;
import static io.jburrow.protocol.wire.FrameType.OPEN;
import static io.jburrow.protocol.wire.FrameType.PING;
import static io.jburrow.protocol.wire.FrameType.PONG;
import static io.jburrow.protocol.wire.FrameType.REGISTER;

import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One control session as the client sees it: its state ({@code docs/protocol.md} §8), the tunnels the server created,
 * and the streams the server opened. The server is untrusted input here, exactly as the client is on the server: every
 * frame is checked against the session's state, and every decision is a return value or a {@link ProtocolException}
 * for the caller to act on.
 *
 * <p>The client doesn't check {@code limits.maxConcurrentStreams}: it binds the server, and the client's view of which
 * streams are open can lag behind the server's (§8.5).
 *
 * <p>Not thread-safe. Confined to the event loop of the session's connection.
 */
public final class ClientSession {

    /** The frames a server may send in each state, {@code docs/protocol.md} §8.2. */
    private static final Map<ClientSessionState, Set<FrameType>> ALLOWED_INBOUND =
            new EnumMap<>(ClientSessionState.class);

    private static final StreamOutcome DISCARD = new StreamOutcome.Discard();

    static {
        ALLOWED_INBOUND.put(ClientSessionState.CONNECTING, EnumSet.noneOf(FrameType.class));
        ALLOWED_INBOUND.put(ClientSessionState.AWAITING_HELLO, EnumSet.of(HELLO, ERROR));
        ALLOWED_INBOUND.put(ClientSessionState.AWAITING_REGISTERED, EnumSet.of(REGISTER, PING, PONG, ERROR));
        ALLOWED_INBOUND.put(ClientSessionState.ESTABLISHED, EnumSet.of(OPEN, DATA, CLOSE, PING, PONG, ERROR));
        ALLOWED_INBOUND.put(ClientSessionState.CLOSING, EnumSet.allOf(FrameType.class));
    }

    private final List<TunnelSpec> requested;
    /** Insertion order is opening order, so iteration is by increasing id. */
    private final Map<Integer, StreamLifecycle> streams = new LinkedHashMap<>();

    private ClientSessionState state = ClientSessionState.CONNECTING;
    private UUID sessionId;
    private SessionLimits limits;
    private Map<UUID, ActiveTunnel> tunnels = Map.of();
    private int highestStreamId = StreamIds.CONTROL;

    /** @param requested the tunnels to ask for, in order; at least one, with distinct refs */
    public ClientSession(List<TunnelSpec> requested) {
        this.requested = List.copyOf(Objects.requireNonNull(requested, "requested"));
        if (this.requested.isEmpty()) {
            throw new IllegalArgumentException("A session asks for at least one tunnel");
        }
        if (this.requested.stream().map(TunnelSpec::ref).distinct().count() != this.requested.size()) {
            throw new IllegalArgumentException("Each tunnel of a session needs its own ref");
        }
    }

    public ClientSessionState state() {
        return state;
    }

    public boolean isEstablished() {
        return state == ClientSessionState.ESTABLISHED;
    }

    public boolean isClosing() {
        return state == ClientSessionState.CLOSING;
    }

    /** The {@code REGISTER} this session sends, with {@code authtoken}. */
    public Register register(String authtoken) {
        return new Register(
                authtoken, requested.stream().map(TunnelSpec::toRequest).toList());
    }

    /** The WebSocket upgrade completed and {@code HELLO} and {@code REGISTER} went out: {@code CONNECTING -> AWAITING_HELLO}. */
    public void onUpgraded() {
        if (state != ClientSessionState.CONNECTING) {
            throw new IllegalStateException("The session was upgraded twice, or after closing: " + state);
        }
        state = ClientSessionState.AWAITING_HELLO;
    }

    /**
     * Checks that a frame of {@code type} from the server is allowed in the current state. In
     * {@link ClientSessionState#CLOSING} every frame passes: the caller ignores it, releasing what it holds.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} for a frame out of order, which includes every
     *     {@code WINDOW_UPDATE} and {@code GOAWAY} in this draft
     */
    public void checkInbound(FrameType type) {
        Objects.requireNonNull(type, "type");
        if (!ALLOWED_INBOUND.get(state).contains(type)) {
            throw protocolError(type + " is not allowed while the session is " + describe(state) + ".");
        }
    }

    /**
     * The server's {@code HELLO}: {@code AWAITING_HELLO -> AWAITING_REGISTERED}. Capabilities the server lists are
     * ignored, since this client offered none (§4.2).
     *
     * @return the limits the client must stay within
     */
    public SessionLimits onServerHello(ServerHello hello) {
        Objects.requireNonNull(hello, "hello");
        require(ClientSessionState.AWAITING_HELLO, HELLO);
        sessionId = hello.sessionId();
        limits = hello.limits();
        state = ClientSessionState.AWAITING_REGISTERED;
        return limits;
    }

    /**
     * The server's {@code REGISTER} reply: {@code AWAITING_REGISTERED -> ESTABLISHED}. It must answer the request entry
     * for entry, in order, with the same {@code ref} and {@code kind}, the requested port where one was asked for, and
     * a tunnel id of its own for each (§8.4).
     *
     * @return the tunnels, in request order
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} for a reply out of order or one that doesn't match
     */
    public List<ActiveTunnel> onRegistered(Registered reply) {
        Objects.requireNonNull(reply, "reply");
        require(ClientSessionState.AWAITING_REGISTERED, REGISTER);
        List<RegisteredTunnel> answers = reply.tunnels();
        if (answers.size() != requested.size()) {
            throw protocolError(
                    "The REGISTER reply has " + answers.size() + " tunnels for a request of " + requested.size() + ".");
        }
        Map<UUID, ActiveTunnel> byId = new LinkedHashMap<>();
        for (int i = 0; i < answers.size(); i++) {
            RegisteredTunnel answer = answers.get(i);
            TunnelRequest request = requested.get(i).toRequest();
            if (!answer.ref().equals(request.ref()) || answer.kind() != request.kind()) {
                throw protocolError("tunnels[" + i + "] of the REGISTER reply doesn't answer request entry " + i + ".");
            }
            if (request.remotePort() != null && answer.publicPort() != request.remotePort()) {
                throw protocolError("tunnels[" + i + "] of the REGISTER reply isn't on the requested port.");
            }
            if (byId.put(
                            answer.tunnelId(),
                            new ActiveTunnel(answer, requested.get(i).target()))
                    != null) {
                throw protocolError("tunnels[" + i + "] of the REGISTER reply repeats a tunnelId.");
            }
        }
        tunnels = byId;
        state = ClientSessionState.ESTABLISHED;
        return List.copyOf(byId.values());
    }

    /**
     * The server opened stream {@code streamId} for a visitor of one of this session's tunnels (§9.4). The stream is
     * open from this moment.
     *
     * @return the tunnel whose local target the stream is forwarded to
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} for an id not above every id seen, or an unknown
     *     tunnel or kind
     */
    public ActiveTunnel onOpen(int streamId, Open open) {
        Objects.requireNonNull(open, "open");
        require(ClientSessionState.ESTABLISHED, OPEN);
        if (streamId <= highestStreamId) {
            throw protocolError("OPEN on stream " + streamId + " is not above every stream id the session has seen.");
        }
        ActiveTunnel tunnel = tunnels.get(open.tunnelId());
        if (tunnel == null) {
            throw protocolError("OPEN on stream " + streamId + " names a tunnel this session doesn't have.");
        }
        if (open.kind() != tunnel.registered().kind()) {
            throw protocolError("OPEN on stream " + streamId + " has a kind its tunnel doesn't.");
        }
        highestStreamId = streamId;
        streams.put(streamId, new StreamLifecycle());
        return tunnel;
    }

    /**
     * A {@code DATA}, {@code CLOSE} or {@code CLOSE RST} from the server on {@code streamId}. A frame for a stream that
     * existed and closed is {@link StreamOutcome.Discard} (§9.3).
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public StreamOutcome onStreamFrame(int streamId, StreamEvent received) {
        Objects.requireNonNull(received, "received");
        if (!received.isReceived()) {
            throw new IllegalArgumentException(received + " is not an event from the server");
        }
        Optional<StreamLifecycle> lifecycle = lookup(streamId);
        if (lifecycle.isEmpty()) {
            return DISCARD;
        }
        return apply(streamId, lifecycle.get(), received);
    }

    /**
     * Records what this client sent on {@code streamId}.
     *
     * @throws IllegalStateException if the stream isn't open or its state forbids the send: a bug in the caller
     */
    public void onStreamSent(int streamId, StreamEvent sent) {
        Objects.requireNonNull(sent, "sent");
        if (sent.isReceived()) {
            throw new IllegalArgumentException(sent + " is not an event from this client");
        }
        StreamLifecycle lifecycle = streams.get(streamId);
        if (lifecycle == null) {
            throw new IllegalStateException("Stream " + streamId + " is not open");
        }
        apply(streamId, lifecycle, sent);
    }

    /**
     * Where stream {@code streamId} is, or empty once it has closed.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public Optional<StreamState> streamState(int streamId) {
        return lookup(streamId).map(StreamLifecycle::state);
    }

    /** Every stream not yet closed, in increasing order. */
    public List<Integer> openStreamIds() {
        return List.copyOf(streams.keySet());
    }

    /** Starts closing, from any state. Does nothing if the session is already closing. */
    public void onClosing() {
        state = ClientSessionState.CLOSING;
    }

    /** The id the server gave the session in its {@code HELLO}, once received. */
    public Optional<UUID> sessionId() {
        return Optional.ofNullable(sessionId);
    }

    /** The server's limits from its {@code HELLO}, once received. */
    public Optional<SessionLimits> limits() {
        return Optional.ofNullable(limits);
    }

    /** The longest {@code DATA} payload this client may send: the server's limit, or the protocol's before it is known. */
    public int maxFramePayload() {
        return limits == null ? FrameLimits.MAX_DATA_PAYLOAD : limits.maxFramePayload();
    }

    /** The tunnels, in request order; empty until established. */
    public List<ActiveTunnel> tunnels() {
        return List.copyOf(tunnels.values());
    }

    private Optional<StreamLifecycle> lookup(int streamId) {
        if (streamId <= StreamIds.CONTROL) {
            throw new IllegalArgumentException("Not a data stream id: " + streamId);
        }
        if (streamId > highestStreamId) {
            throw protocolError("Stream " + streamId + " was never opened.");
        }
        return Optional.ofNullable(streams.get(streamId));
    }

    private StreamOutcome apply(int streamId, StreamLifecycle lifecycle, StreamEvent event) {
        StreamOutcome outcome = lifecycle.on(event);
        if (lifecycle.isClosed()) {
            streams.remove(streamId);
        }
        return outcome;
    }

    private void require(ClientSessionState expected, FrameType type) {
        if (state != expected) {
            throw protocolError(type + " is not allowed while the session is " + describe(state) + ".");
        }
    }

    private static ProtocolException protocolError(String message) {
        return ProtocolException.session(ErrorCode.PROTOCOL_ERROR, message);
    }

    private static String describe(ClientSessionState state) {
        return switch (state) {
            case CONNECTING -> "connecting";
            case AWAITING_HELLO -> "awaiting HELLO";
            case AWAITING_REGISTERED -> "awaiting the REGISTER reply";
            case ESTABLISHED -> "established";
            case CLOSING -> "closing";
        };
    }
}
