package io.jburrow.client.tunnel.domain;

import static io.jburrow.protocol.wire.FrameType.CLOSE;
import static io.jburrow.protocol.wire.FrameType.DATA;
import static io.jburrow.protocol.wire.FrameType.ERROR;
import static io.jburrow.protocol.wire.FrameType.HELLO;
import static io.jburrow.protocol.wire.FrameType.OPEN;
import static io.jburrow.protocol.wire.FrameType.PING;
import static io.jburrow.protocol.wire.FrameType.PONG;
import static io.jburrow.protocol.wire.FrameType.REGISTER;
import static io.jburrow.protocol.wire.FrameType.WINDOW_UPDATE;

import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.flow.SessionFlow;
import io.jburrow.protocol.flow.StreamFlow;
import io.jburrow.protocol.flow.UpdateTarget;
import io.jburrow.protocol.stream.HttpExchangeRules;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import java.util.Collections;
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
 * One control session as the client sees it: its state ({@code docs/protocol.md} §8), the capabilities it negotiated
 * (§4.2), the tunnels the server created, the streams the server opened, their flow control (§10), and the rules of
 * each HTTP stream's exchange (§9.7). The server is untrusted input here, exactly as the client is on the server: every
 * frame is checked against the session's state, windows and exchange rules, and every decision is a return value or a
 * {@link ProtocolException} for the caller to act on. The stream relay learns everything about a stream here, never
 * from the flow model directly.
 *
 * <p>A session asking for an HTTP tunnel sends its {@code REGISTER} only once the server's {@code HELLO} has listed
 * {@code http} (§8.3); one asking only for TCP tunnels sends it with its {@code HELLO}.
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
        ALLOWED_INBOUND.put(
                ClientSessionState.ESTABLISHED, EnumSet.of(OPEN, DATA, WINDOW_UPDATE, CLOSE, PING, PONG, ERROR));
        ALLOWED_INBOUND.put(ClientSessionState.CLOSING, EnumSet.allOf(FrameType.class));
    }

    private final List<TunnelSpec> requested;
    private final Windows own;
    private final Set<Capability> offered;
    /** Insertion order is opening order, so iteration is by increasing id. */
    private final Map<Integer, Entry> streams = new LinkedHashMap<>();

    private ClientSessionState state = ClientSessionState.CONNECTING;
    private UUID sessionId;
    private SessionLimits limits;
    private Set<Capability> capabilities = Set.of();
    private Map<UUID, ActiveTunnel> tunnels = Map.of();
    private int highestStreamId = StreamIds.CONTROL;
    /** Created by the server's {@code HELLO}, which carries its windows. */
    private SessionFlow flow;

    /** A session that offers no capability, so it asks for TCP tunnels only. */
    public ClientSession(List<TunnelSpec> requested, Windows own) {
        this(requested, own, Set.of());
    }

    /**
     * @param requested the tunnels to ask for, in order; at least one, with distinct refs
     * @param own this client's receive windows, announced in its {@code HELLO}
     * @param offered the capabilities this client offers in its {@code HELLO}; every capability a requested tunnel
     *     needs
     */
    public ClientSession(List<TunnelSpec> requested, Windows own, Set<Capability> offered) {
        this.own = Objects.requireNonNull(own, "own");
        this.requested = List.copyOf(Objects.requireNonNull(requested, "requested"));
        this.offered = Collections.unmodifiableSet(
                Objects.requireNonNull(offered, "offered").isEmpty()
                        ? EnumSet.noneOf(Capability.class)
                        : EnumSet.copyOf(offered));
        if (this.requested.isEmpty()) {
            throw new IllegalArgumentException("A session asks for at least one tunnel");
        }
        if (this.requested.stream().map(TunnelSpec::ref).distinct().count() != this.requested.size()) {
            throw new IllegalArgumentException("Each tunnel of a session needs its own ref");
        }
        if (registersAfterHello() && !this.offered.contains(Capability.HTTP)) {
            throw new IllegalArgumentException("An HTTP tunnel needs a session that offers the http capability");
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

    /** The {@code HELLO} this session sends, from {@code client}: its offered capabilities and receive windows. */
    public Hello hello(ClientInfo client) {
        return new Hello(client, offered.stream().map(Capability::wireName).toList(), own);
    }

    /** The {@code REGISTER} this session sends, with {@code authtoken}. */
    public Register register(String authtoken) {
        return new Register(
                authtoken, requested.stream().map(TunnelSpec::toRequest).toList());
    }

    /**
     * Whether {@code REGISTER} waits for the server's {@code HELLO}: it asks for an HTTP tunnel, which only a session
     * the server has given {@code http} may (§4.2).
     */
    public boolean registersAfterHello() {
        return requested.stream().anyMatch(spec -> spec.kind() == TunnelKind.HTTP);
    }

    /**
     * The WebSocket upgrade completed and {@code HELLO} went out, with {@code REGISTER} unless it
     * {@linkplain #registersAfterHello waits}: {@code CONNECTING -> AWAITING_HELLO}.
     */
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
     *     {@code GOAWAY} in this draft
     */
    public void checkInbound(FrameType type) {
        Objects.requireNonNull(type, "type");
        if (!ALLOWED_INBOUND.get(state).contains(type)) {
            throw protocolError(type + " is not allowed while the session is " + describe(state) + ".");
        }
    }

    /** The receive windows this client announces in its {@code HELLO}. */
    public Windows windows() {
        return own;
    }

    /**
     * The server's {@code HELLO}: {@code AWAITING_HELLO -> AWAITING_REGISTERED}. Its windows, with this client's own,
     * set the session's flow control, and the capabilities it lists that this client offered are the session's; the
     * others are ignored (§4.2).
     *
     * @return the limits the client must stay within
     */
    public SessionLimits onServerHello(ServerHello hello) {
        Objects.requireNonNull(hello, "hello");
        require(ClientSessionState.AWAITING_HELLO, HELLO);
        Windows remote = hello.windows();
        flow = new SessionFlow(own.stream(), own.session(), remote.stream(), remote.session());
        Set<Capability> agreed = EnumSet.noneOf(Capability.class);
        for (String name : hello.capabilities()) {
            Capability.fromWireName(name).filter(offered::contains).ifPresent(agreed::add);
        }
        capabilities = Collections.unmodifiableSet(agreed);
        sessionId = hello.sessionId();
        limits = hello.limits();
        state = ClientSessionState.AWAITING_REGISTERED;
        return limits;
    }

    /** The capabilities this client offers in its {@code HELLO}. */
    public Set<Capability> offered() {
        return offered;
    }

    /** The capabilities both sides support, as the server's {@code HELLO} listed them. Empty until it arrives. */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    /** Whether the session has {@code http}: what the codec and every rule scoped to HTTP tunnels read. */
    public boolean httpEnabled() {
        return capabilities.contains(Capability.HTTP);
    }

    /**
     * A capability the requested tunnels need that the server's {@code HELLO} didn't list. Such a session can't
     * register, and ends without sending {@code REGISTER}.
     */
    public Optional<Capability> missingCapability() {
        return registersAfterHello() && !httpEnabled() ? Optional.of(Capability.HTTP) : Optional.empty();
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
            if (request.subdomain() != null && !answer.publicHost().startsWith(request.subdomain() + ".")) {
                throw protocolError("tunnels[" + i + "] of the REGISTER reply isn't on the requested subdomain.");
            }
            if (byId.put(answer.tunnelId(), new ActiveTunnel(answer, requested.get(i))) != null) {
                throw protocolError("tunnels[" + i + "] of the REGISTER reply repeats a tunnelId.");
            }
        }
        tunnels = byId;
        state = ClientSessionState.ESTABLISHED;
        return List.copyOf(byId.values());
    }

    /**
     * The server opened stream {@code streamId} for a visitor of one of this session's tunnels (§9.4). The stream is
     * open from this moment. A stream of an HTTP tunnel carries the request head of {@code open}, and the rules of its
     * exchange hold from here on (§9.7).
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
        HttpExchangeRules rules = open.kind() == TunnelKind.HTTP ? HttpExchangeRules.atClient(open.request()) : null;
        streams.put(streamId, new Entry(new StreamLifecycle(), requireFlow().openStream(), rules));
        return tunnel;
    }

    /**
     * The most {@code DATA} this client may send on {@code streamId} right now: the smaller of the stream's credit and
     * the session's. 0 if the stream is closed or this client has half-closed it.
     */
    public int sendable(int streamId) {
        Entry entry = streams.get(streamId);
        if (entry == null || !sendsOn(entry.lifecycle().state())) {
            return 0;
        }
        return flow.sendable(entry.flow());
    }

    /**
     * What this client may still send on {@code streamId} by its own credit alone, ignoring the session's. 0 if the
     * stream is closed or this client has half-closed it. Tells a sender blocked by its stream from one blocked by the
     * session.
     */
    public int streamCredit(int streamId) {
        Entry entry = streams.get(streamId);
        if (entry == null || !sendsOn(entry.lifecycle().state())) {
            return 0;
        }
        return (int) entry.flow().send().available();
    }

    /** What this client may still send on all streams together; 0 before the server's {@code HELLO}. */
    public int sessionCredit() {
        return flow == null ? 0 : (int) flow.sessionSend().available();
    }

    /**
     * Records {@code DATA} of {@code bytes} sent on {@code streamId}, spending its stream and session credit.
     *
     * @throws IllegalStateException if the stream isn't open for sending, {@code bytes} is more than
     *     {@link #sendable}, or it breaks the response's framing: a bug in the caller
     */
    public void onDataSent(int streamId, int bytes) {
        Entry entry = requireOpen(streamId);
        if (!sendsOn(entry.lifecycle().state())) {
            throw new IllegalStateException("Stream " + streamId + " is "
                    + entry.lifecycle().state() + " and takes no more DATA from this client");
        }
        if (entry.isHttp()) {
            entry.rules().onResponseData(bytes);
        }
        flow.onSent(entry.flow(), bytes);
        apply(streamId, entry, StreamEvent.DATA_SENT);
    }

    /**
     * Records {@code HEADERS} sent on {@code streamId}: a response head of its exchange. Not flow-controlled.
     *
     * @throws IllegalStateException if the stream isn't an open HTTP stream this client still sends on, or the head
     *     breaks the exchange's rules: a bug in the caller
     */
    public void onHeadersSent(int streamId, HttpResponseHead head) {
        Objects.requireNonNull(head, "head");
        Entry entry = requireOpen(streamId);
        if (!entry.isHttp()) {
            throw new IllegalStateException("Stream " + streamId + " carries a TCP connection and takes no HEADERS");
        }
        apply(streamId, entry, StreamEvent.HEADERS_SENT);
        entry.rules().onResponseHead(head);
    }

    /**
     * {@code DATA} of {@code bytes} from the server on {@code streamId}. The stream's lifecycle decides first, then an
     * HTTP stream's rules, then the windows, so a frame that is a stream error is never also taken for an overrun of
     * the stream window. Bytes it returns as {@link DataOutcome.Deliver} are held until {@link #onReleased}; the other
     * outcomes have released them.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened, or the {@code DATA}
     *     breaks its request body's framing; {@link ErrorCode#FLOW_CONTROL_ERROR} if {@code bytes} overruns the
     *     session window, or an open stream's window
     */
    public DataOutcome onDataReceived(int streamId, int bytes) {
        SessionFlow flow = requireFlow();
        Optional<Entry> entry = lookup(streamId);
        if (entry.isEmpty()) {
            return new DataOutcome.Discard(flow.onDiscarded(bytes));
        }
        StreamOutcome outcome = apply(streamId, entry.get(), StreamEvent.DATA_RECEIVED);
        return switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                if (entry.get().isHttp()) {
                    entry.get().rules().onRequestData(bytes);
                }
                flow.onReceived(entry.get().flow(), bytes);
                yield DataOutcome.DELIVER;
            }
            case StreamOutcome.ResetStream reset -> new DataOutcome.Reset(reset.code(), flow.onDiscarded(bytes));
            case StreamOutcome.Discard discarded -> new DataOutcome.Discard(flow.onDiscarded(bytes));
        };
    }

    /**
     * A {@code WINDOW_UPDATE} from the server: on stream 0 for the session, otherwise for {@code streamId}. The caller
     * resumes senders only for {@link UpdateTarget#SESSION} and {@link UpdateTarget#STREAM}.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened;
     *     {@link ErrorCode#FLOW_CONTROL_ERROR} if the credit would pass 2^31 − 1
     */
    public UpdateTarget onWindowUpdate(int streamId, int increment) {
        SessionFlow flow = requireFlow();
        if (streamId == StreamIds.CONTROL) {
            flow.onSessionUpdate(increment);
            return UpdateTarget.SESSION;
        }
        Optional<Entry> entry = lookup(streamId);
        if (entry.isEmpty()) {
            return UpdateTarget.DISCARDED;
        }
        StreamOutcome outcome = apply(streamId, entry.get(), StreamEvent.WINDOW_UPDATE_RECEIVED);
        return switch (outcome) {
            case StreamOutcome.Accept accepted
            when accepted.next() == StreamState.HALF_CLOSED_LOCAL -> UpdateTarget.IGNORED;
            case StreamOutcome.Accept accepted -> {
                flow.onStreamUpdate(entry.get().flow(), increment);
                yield UpdateTarget.STREAM;
            }
            case StreamOutcome.Discard discarded -> UpdateTarget.DISCARDED;
            case StreamOutcome.ResetStream reset ->
                throw new IllegalStateException("A WINDOW_UPDATE reset stream " + streamId + " with " + reset.code());
        };
    }

    /**
     * {@code bytes} of {@code DATA} received on {@code streamId} that this client no longer holds: written to the local
     * connection, successfully or not, or dropped before it was made. Once the stream has closed, only session credit
     * comes back.
     *
     * @return the {@code WINDOW_UPDATE}s to send now
     * @throws IllegalStateException if more is released than is held
     */
    public Credit onReleased(int streamId, int bytes) {
        SessionFlow flow = requireFlow();
        Entry entry = streams.get(streamId);
        if (entry == null) {
            return flow.onReleased(null, bytes);
        }
        Credit credit = flow.onReleased(entry.flow(), bytes);
        if (credit.stream() > 0) {
            apply(streamId, entry, StreamEvent.WINDOW_UPDATE_SENT);
        }
        return credit;
    }

    /**
     * A {@code CLOSE} or {@code CLOSE RST} from the server on {@code streamId}. A frame for a stream that existed and
     * closed is {@link StreamOutcome.Discard} (§9.3). {@code DATA} and {@code WINDOW_UPDATE} have their own methods,
     * since they carry credit.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened, or a {@code CLOSE}
     *     ends a request body short of its {@code content-length}
     */
    public StreamOutcome onStreamFrame(int streamId, StreamEvent received) {
        Objects.requireNonNull(received, "received");
        if (received != StreamEvent.CLOSE_RECEIVED && received != StreamEvent.RST_RECEIVED) {
            throw new IllegalArgumentException(received + " is not a CLOSE from the server");
        }
        Optional<Entry> entry = lookup(streamId);
        if (entry.isEmpty()) {
            return DISCARD;
        }
        StreamOutcome outcome = apply(streamId, entry.get(), received);
        if (outcome instanceof StreamOutcome.Accept accepted) {
            if (received == StreamEvent.CLOSE_RECEIVED && entry.get().isHttp()) {
                entry.get().rules().onRequestEnd();
            }
            if (accepted.next() == StreamState.HALF_CLOSED_REMOTE) {
                flow.onPeerFinished(entry.get().flow());
            }
        }
        return outcome;
    }

    /**
     * Records a {@code CLOSE} or {@code CLOSE RST} this client sent on {@code streamId}. {@code DATA} is recorded by
     * {@link #onDataSent}, {@code HEADERS} by {@link #onHeadersSent}, {@code WINDOW_UPDATE} by {@link #onReleased}.
     *
     * @throws IllegalStateException if the stream isn't open, its state forbids the send, or a {@code CLOSE} ends a
     *     response its rules say isn't complete: a bug in the caller
     */
    public void onStreamSent(int streamId, StreamEvent sent) {
        Objects.requireNonNull(sent, "sent");
        if (sent != StreamEvent.CLOSE_SENT && sent != StreamEvent.RST_SENT) {
            throw new IllegalArgumentException(sent + " is not a CLOSE from this client");
        }
        Entry entry = requireOpen(streamId);
        if (sent == StreamEvent.CLOSE_SENT && entry.isHttp()) {
            entry.rules().onResponseEnd();
        }
        apply(streamId, entry, sent);
    }

    /**
     * Where stream {@code streamId} is, or empty once it has closed.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public Optional<StreamState> streamState(int streamId) {
        return lookup(streamId).map(entry -> entry.lifecycle().state());
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

    private Optional<Entry> lookup(int streamId) {
        if (streamId <= StreamIds.CONTROL) {
            throw new IllegalArgumentException("Not a data stream id: " + streamId);
        }
        if (streamId > highestStreamId) {
            throw protocolError("Stream " + streamId + " was never opened.");
        }
        return Optional.ofNullable(streams.get(streamId));
    }

    private Entry requireOpen(int streamId) {
        Entry entry = streams.get(streamId);
        if (entry == null) {
            throw new IllegalStateException("Stream " + streamId + " is not open");
        }
        return entry;
    }

    private StreamOutcome apply(int streamId, Entry entry, StreamEvent event) {
        StreamOutcome outcome = entry.lifecycle().on(event);
        if (entry.lifecycle().isClosed()) {
            streams.remove(streamId);
        }
        return outcome;
    }

    private SessionFlow requireFlow() {
        if (flow == null) {
            throw new IllegalStateException("The session has no flow control before the server's HELLO");
        }
        return flow;
    }

    /** Whether this client may still send {@code DATA} on a stream in {@code state}. */
    private static boolean sendsOn(StreamState state) {
        return state == StreamState.OPEN || state == StreamState.HALF_CLOSED_REMOTE;
    }

    private void require(ClientSessionState expected, FrameType type) {
        if (state != expected) {
            throw protocolError(type + " is not allowed while the session is " + describe(state) + ".");
        }
    }

    private static ProtocolException protocolError(String message) {
        return ProtocolException.session(ErrorCode.PROTOCOL_ERROR, message);
    }

    /** One open stream: where it is, its credit both ways, and for an HTTP stream the rules of its exchange. */
    private record Entry(StreamLifecycle lifecycle, StreamFlow flow, HttpExchangeRules rules) {

        boolean isHttp() {
            return rules != null;
        }
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
