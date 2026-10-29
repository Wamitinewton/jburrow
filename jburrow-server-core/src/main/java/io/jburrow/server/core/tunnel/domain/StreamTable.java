package io.jburrow.server.core.tunnel.domain;

import io.jburrow.protocol.flow.StreamFlow;
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

/**
 * The streams of one session that are not yet closed, and the highest id it has allocated. Each stream's entry holds
 * its lifecycle and its flow. A stream leaves the table when its lifecycle reaches {@code CLOSED}, and its flow goes
 * with it; its id stays used ({@code docs/protocol.md} §9.1, §9.3).
 *
 * <p>Not thread-safe. Owned by its {@link Session} and confined to the session's event loop.
 */
public final class StreamTable {

    private static final StreamOutcome DISCARD = new StreamOutcome.Discard();

    private final int capacity;
    /** Insertion order is allocation order, so iteration is by increasing id. */
    private final Map<Integer, Entry> open = new LinkedHashMap<>();

    private int highestId = StreamIds.CONTROL;
    private int peakSize;

    public StreamTable(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A stream table needs room for at least one stream: " + capacity);
        }
        this.capacity = capacity;
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
        Objects.requireNonNull(flow, "flow");
        if (isFull()) {
            throw new IllegalStateException("The stream table is full: " + capacity);
        }
        highestId = StreamIds.next(highestId);
        open.put(highestId, new Entry(new StreamLifecycle(), flow));
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
            open.remove(id);
        }
        return outcome;
    }

    /** One open stream: where it is, and its credit both ways. */
    public record Entry(StreamLifecycle lifecycle, StreamFlow flow) {
        public Entry {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(flow, "flow");
        }
    }

    private static void requireDataStream(int id) {
        if (id <= StreamIds.CONTROL) {
            throw new IllegalArgumentException("Not a data stream id: " + id);
        }
    }
}
