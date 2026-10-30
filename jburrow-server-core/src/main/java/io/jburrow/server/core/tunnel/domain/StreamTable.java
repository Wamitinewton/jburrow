package io.jburrow.server.core.tunnel.domain;

import io.jburrow.protocol.flow.StreamFlow;
import io.jburrow.protocol.stream.HttpExchangeRules;
import io.jburrow.protocol.stream.StreamEvent;
import io.jburrow.protocol.stream.StreamLifecycle;
import io.jburrow.protocol.stream.StreamOutcome;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ObjIntConsumer;

/**
 * The streams of one session that are not yet closed, and the highest id it has allocated. Each stream's entry holds
 * its lifecycle and its flow and, for an HTTP stream, the rules of its exchange. A stream leaves the table when its
 * lifecycle reaches {@code CLOSED}, and its entry goes with it, past the table's {@code closed} hook; its id stays used
 * ({@code docs/protocol.md} §9.1, §9.3).
 *
 * <p>Not thread-safe. Owned by its {@link Session} and confined to the session's event loop.
 */
public final class StreamTable {

    private static final StreamOutcome DISCARD = new StreamOutcome.Discard();

    private final int capacity;
    private final ObjIntConsumer<Entry> closed;
    /** Insertion order is allocation order, so iteration is by increasing id. */
    private final Map<Integer, Entry> open = new LinkedHashMap<>();

    private int highestId = StreamIds.CONTROL;
    private int peakSize;

    public StreamTable(int capacity) {
        this(capacity, (entry, id) -> {});
    }

    /** @param closed told of each entry, with its id, as its stream leaves the table */
    public StreamTable(int capacity, ObjIntConsumer<Entry> closed) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A stream table needs room for at least one stream: " + capacity);
        }
        this.capacity = capacity;
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return open.size();
    }

    public boolean isFull() {
        return open.size() >= capacity;
    }

    public int highestId() {
        return highestId;
    }

    /** The most streams the table has held open at once. */
    public int peakSize() {
        return peakSize;
    }

    /**
     * Opens a stream on the next id, with {@code flow} as its accounting.
     *
     * @throws IllegalStateException if the table is full; check {@link #isFull()} first
     * @throws ProtocolException {@link ErrorCode#INTERNAL_ERROR} once every stream id is used
     */
    public int allocate(StreamFlow flow) {
        return allocate(flow, null);
    }

    /**
     * Opens an HTTP stream on the next id, with {@code flow} as its accounting and {@code rules} as its exchange's.
     *
     * @throws IllegalStateException if the table is full; check {@link #isFull()} first
     * @throws ProtocolException {@link ErrorCode#INTERNAL_ERROR} once every stream id is used
     */
    public int allocate(StreamFlow flow, HttpExchangeRules rules) {
        Objects.requireNonNull(flow, "flow");
        if (isFull()) {
            throw new IllegalStateException("The stream table is full: " + capacity);
        }
        highestId = StreamIds.next(highestId);
        open.put(highestId, new Entry(new StreamLifecycle(), flow, rules));
        peakSize = Math.max(peakSize, open.size());
        return highestId;
    }

    /**
     * The entry of stream {@code id}, or empty if the stream existed and is closed. For frames from the peer, which
     * may name any id.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if {@code id} was never opened
     */
    public Optional<Entry> lookup(int id) {
        requireDataStream(id);
        if (id > highestId) {
            throw ProtocolException.session(ErrorCode.PROTOCOL_ERROR, "Stream " + id + " was never opened.");
        }
        return Optional.ofNullable(open.get(id));
    }

    /** The entry of stream {@code id} if it is open. For this side's own calls, which never name an unknown id. */
    public Optional<Entry> find(int id) {
        requireDataStream(id);
        return Optional.ofNullable(open.get(id));
    }

    /**
     * Applies an event the peer caused. A frame for a closed stream is {@link StreamOutcome.Discard}.
     *
     * @throws ProtocolException {@link ErrorCode#PROTOCOL_ERROR} if {@code id} was never opened
     */
    public StreamOutcome onReceived(int id, StreamEvent event) {
        if (!event.isReceived()) {
            throw new IllegalArgumentException(event + " is not an event from the peer");
        }
        Optional<Entry> entry = lookup(id);
        if (entry.isEmpty()) {
            return DISCARD;
        }
        return apply(id, entry.get().lifecycle(), event);
    }

    /**
     * Records an event this side caused.
     *
     * @throws IllegalStateException if the stream isn't open, or its state forbids the event
     */
    public void onSent(int id, StreamEvent event) {
        if (event.isReceived()) {
            throw new IllegalArgumentException(event + " is not an event from this side");
        }
        Entry entry = find(id).orElseThrow(() -> new IllegalStateException("Stream " + id + " is not open"));
        apply(id, entry.lifecycle(), event);
    }

    /** The ids of every stream not yet closed, in increasing order. */
    public List<Integer> openIds() {
        return List.copyOf(open.keySet());
    }

    private StreamOutcome apply(int id, StreamLifecycle lifecycle, StreamEvent event) {
        StreamOutcome outcome = lifecycle.on(event);
        if (lifecycle.isClosed()) {
            closed.accept(open.remove(id), id);
        }
        return outcome;
    }

    /**
     * One open stream: where it is, and its credit both ways.
     *
     * @param rules the rules of the HTTP exchange the stream carries; null for a TCP stream
     */
    public record Entry(StreamLifecycle lifecycle, StreamFlow flow, HttpExchangeRules rules) {
        public Entry {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(flow, "flow");
        }

        public boolean isHttp() {
            return rules != null;
        }
    }

    private static void requireDataStream(int id) {
        if (id <= StreamIds.CONTROL) {
            throw new IllegalArgumentException("Not a data stream id: " + id);
        }
    }
}
