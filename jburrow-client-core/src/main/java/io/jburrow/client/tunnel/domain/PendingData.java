package io.jburrow.client.tunnel.domain;

/**
 * Counts the bytes of a stream the client holds while its local connection is being made ({@code docs/protocol.md}
 * §9.4: opening is optimistic, so {@code DATA} can arrive first). Accounting only; the caller holds the buffers.
 *
 * <p>Holding more than the limit means the caller stops reading the session's connection until the bytes are written
 * or discarded, so the server's {@code DATA} can't grow the client's memory without bound.
 *
 * <p>Not thread-safe. Confined to the thread that owns its session.
 */
public final class PendingData {

    private final long limit;
    private long bytes;

    /** @param limit the most bytes held before the caller stops reading; positive */
    public PendingData(long limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("The pending data limit must be positive: " + limit);
        }
        this.limit = limit;
    }

    /**
     * Counts {@code count} more bytes held.
     *
     * @return true if these bytes took the total over the limit, the moment the caller stops reading
     */
    public boolean add(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("A byte count must not be negative: " + count);
        }
        boolean wasOver = isOverLimit();
        bytes += count;
        return !wasOver && isOverLimit();
    }

    /**
     * Forgets every byte held, because they were written or discarded.
     *
     * @return true if the total was over the limit, so the caller resumes reading
     */
    public boolean clear() {
        boolean wasOver = isOverLimit();
        bytes = 0;
        return wasOver;
    }

    public long bytes() {
        return bytes;
    }

    public boolean isOverLimit() {
        return bytes > limit;
    }
}
