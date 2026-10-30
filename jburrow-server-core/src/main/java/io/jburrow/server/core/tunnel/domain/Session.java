package io.jburrow.server.core.tunnel.domain;

import static io.jburrow.protocol.wire.FrameType.CLOSE;
import static io.jburrow.protocol.wire.FrameType.DATA;
import static io.jburrow.protocol.wire.FrameType.ERROR;
import static io.jburrow.protocol.wire.FrameType.HEADERS;
import static io.jburrow.protocol.wire.FrameType.HELLO;
import static io.jburrow.protocol.wire.FrameType.PING;
import static io.jburrow.protocol.wire.FrameType.PONG;
import static io.jburrow.protocol.wire.FrameType.REGISTER;
import static io.jburrow.protocol.wire.FrameType.WINDOW_UPDATE;

import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.Hello;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.Credit;
import io.jburrow.protocol.flow.DataOutcome;
import io.jburrow.protocol.flow.SessionFlow;
import io.jburrow.protocol.flow.StreamFlow;
import io.jburrow.protocol.flow.UpdateTarget;
import io.jburrow.protocol.stream.HttpExchangeRules;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.stream.StreamState;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.protocol.wire.StreamIds;
import io.jburrow.server.core.tunnel.domain.StreamTable.Entry;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One control session as the server sees it: its state ({@code docs/protocol.md} §8), its tunnels, its streams and
 * their flow control (§10), and the rules of each HTTP stream's exchange (§9.7). Every decision is a return value or a
 * {@link ProtocolException}; the caller does the I/O. The relay learns everything about a stream here, never from the
 * stream table or the flow model directly.
 *
 * <p>A stream that leaves the table gives back whatever send credit it still had reserved. One that leaves with
 * received bytes parked keeps its flow here until they are all released, so a parked byte never returns its session
 * credit twice.
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
        ALLOWED_INBOUND.put(
                SessionState.ESTABLISHED, EnumSet.of(DATA, HEADERS, WINDOW_UPDATE, CLOSE, PING, PONG, ERROR));
        ALLOWED_INBOUND.put(SessionState.CLOSING, EnumSet.allOf(FrameType.class));
    }

    private final SessionId id;
    private final Windows local;
    private final Set<Capability> enabled;
    private final StreamTable streams;
    /** Flows of streams that left the table with bytes parked, until those are released. */
    private final Map<Integer, StreamFlow> departed = new HashMap<>();

    private SessionState state = SessionState.AWAITING_HELLO;
    private Set<Capability> capabilities = Set.of();
    private ClientInfo client;
    private Principal principal;
    private Map<TunnelId, Tunnel> tunnels = Map.of();
    private ErrorCode closeReason;
    /** Created by the client's {@code HELLO}, which carries its windows. */
    private SessionFlow flow;

    /** A session on a server that enables no capability. */
    public Session(SessionId id, int maxConcurrentStreams, Windows local) {
        this(id, maxConcurrentStreams, local, Set.of());
    }

    /**
     * @param local the server's receive windows, announced in its {@code HELLO}
     * @param enabled the capabilities the server enables; the session has those the client also offers
     */
    public Session(SessionId id, int maxConcurrentStreams, Windows local, Set<Capability> enabled) {
        this.id = Objects.requireNonNull(id, "id");
        this.local = Objects.requireNonNull(local, "local");
        this.enabled = Set.copyOf(enabled);
        this.streams = new StreamTable(maxConcurrentStreams, this::onStreamClosed);
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
        if (type == HEADERS && state != SessionState.CLOSING && !httpEnabled()) {
            throw ProtocolException.session(
                    ErrorCode.PROTOCOL_ERROR, "HEADERS is not allowed in a session without the http capability.");
        }
    }

    /**
     * The client's {@code HELLO}: {@code AWAITING_HELLO -> AWAITING_REGISTER}. Its windows, with the server's own, set
     * the session's flow control, and its capabilities, with those the server enables, the session's.
     */
    public void onHello(Hello hello) {
        Objects.requireNonNull(hello, "hello");
        require(SessionState.AWAITING_HELLO, HELLO);
        Windows remote = hello.windows();
        flow = new SessionFlow(local.stream(), local.session(), remote.stream(), remote.session());
        Set<Capability> agreed = EnumSet.noneOf(Capability.class);
        for (String name : ProtocolVersion.negotiate(hello.capabilities(), enabled)) {
            agreed.add(Capability.fromWireName(name).orElseThrow());
        }
        capabilities = Collections.unmodifiableSet(agreed);
        client = hello.client();
        state = SessionState.AWAITING_REGISTER;
    }

    /**
     * The capabilities both sides support, which the server lists in its {@code HELLO}, in the order of
     * {@link Capability}'s constants. Empty until the client's {@code HELLO}, then fixed.
     */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    /** Whether the session has {@code http}: what every rule scoped to HTTP tunnels reads. */
    public boolean httpEnabled() {
        return capabilities.contains(Capability.HTTP);
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
     * Whether a visitor connecting to {@code tunnel} may have a stream as far as this session is concerned. The
     * server-wide quota is the caller's next step, then {@link #openStream}.
     *
     * @return empty if the session can take the stream
     */
    public Optional<StreamAdmission.Refusal> checkAdmission(TunnelId tunnel) {
        Objects.requireNonNull(tunnel, "tunnel");
        if (state != SessionState.ESTABLISHED) {
            return Optional.of(StreamAdmission.Refusal.NOT_ESTABLISHED);
        }
        if (!tunnels.containsKey(tunnel)) {
            throw new IllegalArgumentException("Tunnel " + tunnel + " is not part of session " + id);
        }
        if (streams.isFull()) {
            return Optional.of(StreamAdmission.Refusal.STREAM_LIMIT);
        }
        return Optional.empty();
    }

    /**
     * Opens a stream for a visitor of {@code tunnel}, after {@link #checkAdmission} passed and the quota was taken. The
     * stream is open from this moment: the caller sends {@code OPEN} on the returned id.
     *
     * @throws IllegalStateException if {@link #checkAdmission} would refuse
     * @throws ProtocolException {@link ErrorCode#INTERNAL_ERROR} once every stream id is used
     */
    public int openStream(TunnelId tunnel) {
        Optional<StreamAdmission.Refusal> refusal = checkAdmission(tunnel);
        if (refusal.isPresent()) {
            throw new IllegalStateException(
                    "Session " + id + " can't open a stream: " + refusal.get().label());
        }
        return streams.allocate(requireFlow().openStream());
    }

    /**
     * Opens a stream for an HTTP exchange of {@code tunnel}, carrying {@code request}, after {@link #checkAdmission}
     * passed and the quota was taken. The stream is open from this moment: the caller sends {@code OPEN} on the
     * returned id.
     *
     * @throws IllegalArgumentException if the session doesn't have {@code http}, or {@code tunnel} isn't an HTTP tunnel
     * @throws IllegalStateException if {@link #checkAdmission} would refuse
     * @throws ProtocolException {@link ErrorCode#INTERNAL_ERROR} once every stream id is used
     */
    public int openExchange(TunnelId tunnel, HttpRequestHead request) {
        Objects.requireNonNull(request, "request");
        Optional<StreamAdmission.Refusal> refusal = checkAdmission(tunnel);
        if (refusal.isPresent()) {
            throw new IllegalStateException(
                    "Session " + id + " can't open a stream: " + refusal.get().label());
        }
        if (!httpEnabled() || tunnels.get(tunnel).kind() != TunnelKind.HTTP) {
            throw new IllegalArgumentException("Tunnel " + tunnel + " of session " + id + " has no HTTP exchanges");
        }
        return streams.allocate(requireFlow().openStream(), new HttpExchangeRules(request));
    }

    /**
     * The most {@code DATA} this side may send on {@code streamId} right now: the smaller of the stream's credit and the
     * session's. 0 if the stream is closed or this side has half-closed it.
     */
    public int sendable(int streamId) {
        Optional<Entry> entry = streams.find(streamId);
        if (entry.isEmpty() || !sendsOn(entry.get().lifecycle().state())) {
            return 0;
        }
        return flow.sendable(entry.get().flow());
    }

    /**
     * What this side may still send on {@code streamId} by its own credit alone, ignoring the session's. 0 if the stream
     * is closed or this side has half-closed it. Tells a sender blocked by its stream from one blocked by the session.
     */
    public int streamCredit(int streamId) {
        Optional<Entry> entry = streams.find(streamId);
        if (entry.isEmpty() || !sendsOn(entry.get().lifecycle().state())) {
            return 0;
        }
        return (int) entry.get().flow().send().available();
    }

    /** What this side may still send on all streams together; 0 before the client's {@code HELLO}. */
    public int sessionCredit() {
        return flow == null ? 0 : (int) flow.sessionSend().available();
    }

    /** Send credit reserved on all streams together and neither sent nor refunded yet. */
    public int reserved() {
        return flow == null ? 0 : flow.reserved();
    }

    /**
     * Records {@code DATA} of {@code bytes} sent on {@code streamId}, spending its stream and session credit.
     *
     * @throws IllegalStateException if the stream isn't open for sending, or {@code bytes} is more than
     *     {@link #sendable}: a bug in the caller
     */
    public void onDataSent(int streamId, int bytes) {
        Entry entry = requireOpen(streamId);
        if (!sendsOn(entry.lifecycle().state())) {
            throw new IllegalStateException("Stream " + streamId + " is "
                    + entry.lifecycle().state() + " and takes no more DATA from this side");
        }
        flow.onSent(entry.flow(), bytes);
        if (entry.isHttp()) {
            entry.rules().onRequestData(bytes);
        }
        streams.onSent(streamId, StreamEvent.DATA_SENT);
    }

    /**
     * Reserves up to {@code max} bytes of {@code streamId}'s credit and the session's, for a read whose size isn't known
     * until it is done. What the read produces goes out with {@link #onReservedDataSent}, and the rest comes back with
     * {@link #refund}. The caller folds the session channel's headroom into {@code max}.
     *
     * @return the bytes reserved; 0 if there is no credit, or the stream is closed or half-closed by this side
     */
    public int reserve(int streamId, int max) {
        Optional<Entry> entry = streams.find(streamId);
        if (entry.isEmpty() || !sendsOn(entry.get().lifecycle().state())) {
            return 0;
        }
        return flow.reserve(entry.get().flow(), max);
    }

    /**
     * Records {@code DATA} of {@code bytes} sent on {@code streamId} out of its reservation.
     *
     * @throws IllegalStateException if the stream isn't open for sending, or {@code bytes} is more than it has reserved,
     *     or more than its request body allows: a bug in the caller
     */
    public void onReservedDataSent(int streamId, int bytes) {
        Entry entry = requireOpen(streamId);
        if (!sendsOn(entry.lifecycle().state())) {
            throw new IllegalStateException("Stream " + streamId + " is "
                    + entry.lifecycle().state() + " and takes no more DATA from this side");
        }
        flow.onSentReserved(entry.flow(), bytes);
        if (entry.isHttp()) {
            entry.rules().onRequestData(bytes);
        }
        streams.onSent(streamId, StreamEvent.DATA_SENT);
    }

    /**
     * Gives back {@code bytes} of {@code streamId}'s reservation that its read didn't use. A stream that has closed gave
     * back everything it had reserved as it left, so a refund for it changes nothing.
     *
     * @throws IllegalStateException if {@code bytes} is more than the stream has reserved
     */
    public void refund(int streamId, int bytes) {
        streams.find(streamId).ifPresent(entry -> flow.refund(entry.flow(), bytes));
    }

    /**
     * {@code DATA} of {@code bytes} from the client on {@code streamId}. The stream's lifecycle decides first, then an
     * HTTP stream's rules, then the windows, so a frame that is a stream error is never also taken for an overrun of
     * the stream window. Bytes it returns as {@link DataOutcome.Deliver} are held until {@link #onReleased}; the other
     * outcomes have released them.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened, or the {@code DATA}
     *     breaks its HTTP stream's rules; {@link ErrorCode#FLOW_CONTROL_ERROR} if {@code bytes} overruns the session
     *     window, or an open stream's window
     */
    public DataOutcome onDataReceived(int streamId, int bytes) {
        SessionFlow flow = requireFlow();
        Optional<Entry> entry = streams.lookup(streamId);
        if (entry.isEmpty()) {
            return new DataOutcome.Discard(flow.onDiscarded(bytes));
        }
        StreamOutcome outcome = streams.onReceived(streamId, StreamEvent.DATA_RECEIVED);
        return switch (outcome) {
            case StreamOutcome.Accept accepted -> {
                if (entry.get().isHttp()) {
                    entry.get().rules().onResponseData(bytes);
                }
                flow.onReceived(entry.get().flow(), bytes);
                yield DataOutcome.DELIVER;
            }
            case StreamOutcome.ResetStream reset -> new DataOutcome.Reset(reset.code(), flow.onDiscarded(bytes));
            case StreamOutcome.Discard discarded -> new DataOutcome.Discard(flow.onDiscarded(bytes));
        };
    }

    /**
     * A {@code WINDOW_UPDATE} from the client: on stream 0 for the session, otherwise for {@code streamId}. The caller
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
        Optional<Entry> entry = streams.lookup(streamId);
        if (entry.isEmpty()) {
            return UpdateTarget.DISCARDED;
        }
        StreamOutcome outcome = streams.onReceived(streamId, StreamEvent.WINDOW_UPDATE_RECEIVED);
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
     * {@code bytes} of {@code DATA} received on {@code streamId} that this side no longer holds: written to the visitor,
     * successfully or not. Once the stream has left the table, only session credit comes back, and none for bytes it
     * had parked.
     *
     * @return the {@code WINDOW_UPDATE}s to send now
     * @throws IllegalStateException if more is released than is held
     */
    public Credit onReleased(int streamId, int bytes) {
        SessionFlow flow = requireFlow();
        Optional<Entry> entry = streams.find(streamId);
        if (entry.isEmpty()) {
            StreamFlow gone = departed.get(streamId);
            if (gone == null) {
                return flow.onReleased(null, bytes);
            }
            Credit credit = flow.onReleased(gone, bytes);
            if (gone.receive().held() == 0) {
                departed.remove(streamId);
            }
            return credit;
        }
        Credit credit = flow.onReleased(entry.get().flow(), bytes);
        if (credit.stream() > 0) {
            streams.onSent(streamId, StreamEvent.WINDOW_UPDATE_SENT);
        }
        return credit;
    }

    /**
     * A {@code CLOSE} or {@code CLOSE RST} from the client on {@code streamId}. {@code DATA} and {@code WINDOW_UPDATE}
     * have their own methods, since they carry credit.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public StreamOutcome onStreamFrame(int streamId, StreamEvent received) {
        Objects.requireNonNull(received, "received");
        if (received != StreamEvent.CLOSE_RECEIVED && received != StreamEvent.RST_RECEIVED) {
            throw new IllegalArgumentException(received + " is not a CLOSE from the peer");
        }
        Optional<Entry> entry = streams.lookup(streamId);
        StreamOutcome outcome = streams.onReceived(streamId, received);
        if (entry.isPresent() && outcome instanceof StreamOutcome.Accept accepted) {
            if (received == StreamEvent.CLOSE_RECEIVED && entry.get().isHttp()) {
                entry.get().rules().onResponseEnd();
            }
            if (accepted.next() == StreamState.HALF_CLOSED_REMOTE) {
                requireFlow().onPeerFinished(entry.get().flow());
            }
        }
        return outcome;
    }

    /**
     * A {@code HEADERS} from the client on {@code streamId}: a response head of the HTTP exchange the stream carries.
     * The lifecycle decides first, then the stream's rules.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened, is a TCP tunnel's, or
     *     already has its final head; {@link ErrorCode#VALIDATION_FAILED} if a final head that isn't empty answers
     *     {@code HEAD}
     */
    public StreamOutcome onHeadersReceived(int streamId, HttpResponseHead head) {
        Objects.requireNonNull(head, "head");
        Optional<Entry> entry = streams.lookup(streamId);
        if (entry.isPresent() && !entry.get().isHttp()) {
            throw ProtocolException.session(
                    ErrorCode.PROTOCOL_ERROR, "HEADERS on stream " + streamId + ", which carries a TCP connection.");
        }
        StreamOutcome outcome = streams.onReceived(streamId, StreamEvent.HEADERS_RECEIVED);
        if (outcome instanceof StreamOutcome.Accept) {
            entry.orElseThrow().rules().onResponseHead(head);
        }
        return outcome;
    }

    /**
     * Records a {@code CLOSE} or {@code CLOSE RST} this side sent on {@code streamId}. A send the stream's state forbids
     * is a bug in the caller. {@code DATA} is recorded by {@link #onDataSent}, {@code WINDOW_UPDATE} by
     * {@link #onReleased}.
     */
    public void onStreamSent(int streamId, StreamEvent sent) {
        Objects.requireNonNull(sent, "sent");
        if (sent != StreamEvent.CLOSE_SENT && sent != StreamEvent.RST_SENT) {
            throw new IllegalArgumentException(sent + " is not a CLOSE from this side");
        }
        if (sent == StreamEvent.CLOSE_SENT) {
            streams.find(streamId)
                    .filter(Entry::isHttp)
                    .ifPresent(entry -> entry.rules().onRequestEnd());
        }
        streams.onSent(streamId, sent);
    }

    /**
     * Received bytes {@code streamId} holds and hasn't parked: what {@link #park} would park now. 0 once the stream has
     * left the table, since a stream can't park after that.
     */
    public int parkable(int streamId) {
        return streams.find(streamId)
                .map(entry -> entry.flow().receive().held() - entry.flow().parked())
                .orElse(0);
    }

    /**
     * Treats what {@code streamId} holds as released for the session window only, so its session credit goes back now
     * while the bytes wait for their visitor. Whether and when to park is the caller's policy.
     *
     * @return the session credit to send now; {@link Credit#NONE} if nothing new was parked or the stream has closed
     */
    public Credit park(int streamId) {
        return streams.find(streamId).map(entry -> flow.park(entry.flow())).orElse(Credit.NONE);
    }

    /** Received bytes {@code streamId} holds whose session credit went back when they were parked. */
    public int parked(int streamId) {
        Optional<Entry> entry = streams.find(streamId);
        if (entry.isPresent()) {
            return entry.get().flow().parked();
        }
        StreamFlow gone = departed.get(streamId);
        return gone == null ? 0 : gone.parked();
    }

    /** Every stream not yet closed, in increasing order: what teardown resets. */
    public List<Integer> openStreamIds() {
        return streams.openIds();
    }

    /** The most streams the session has had open at once. */
    public int peakStreams() {
        return streams.peakSize();
    }

    /** The session's credit and windows both ways, for diagnosing a stall. */
    public String describeFlow() {
        return flow == null ? "no flow before the client's HELLO" : flow.toString();
    }

    /** Stream {@code streamId}'s state, credit and windows, for diagnosing a stall. */
    public String describeStream(int streamId) {
        return streams.find(streamId)
                .map(entry -> entry.lifecycle().state() + " " + entry.flow())
                .orElse("closed");
    }

    /**
     * Where stream {@code streamId} is, or empty once it has closed.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if the stream was never opened
     */
    public Optional<StreamState> streamState(int streamId) {
        return streams.lookup(streamId).map(entry -> entry.lifecycle().state());
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

    /**
     * A stream left the table: its reservation goes back, and a flow with bytes parked stays until they are released.
     * Streams exist only once the client's {@code HELLO} has created the flow.
     */
    private void onStreamClosed(Entry entry, int streamId) {
        flow.refundAll(entry.flow());
        if (entry.flow().parked() > 0) {
            flow.onRemoved(entry.flow());
            departed.put(streamId, entry.flow());
        }
    }

    private Entry requireOpen(int streamId) {
        return streams.find(streamId)
                .orElseThrow(() -> new IllegalStateException("Stream " + streamId + " is not open"));
    }

    private SessionFlow requireFlow() {
        if (flow == null) {
            throw new IllegalStateException("Session " + id + " has no flow control before the client's HELLO");
        }
        return flow;
    }

    /** Whether this side may still send {@code DATA} on a stream in {@code state}. */
    private static boolean sendsOn(StreamState state) {
        return state == StreamState.OPEN || state == StreamState.HALF_CLOSED_REMOTE;
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
