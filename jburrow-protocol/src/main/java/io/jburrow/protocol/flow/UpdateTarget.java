package io.jburrow.protocol.flow;

/** Where a received {@code WINDOW_UPDATE}'s credit went. Only {@link #SESSION} and {@link #STREAM} can unblock a sender. */
public enum UpdateTarget {
    /** Stream 0: added to the session's credit. */
    SESSION,
    /** Added to the stream's credit. */
    STREAM,
    /** The stream is half-closed by this side, which sends no more {@code DATA} on it: the credit is useless. */
    IGNORED,
    /** The stream had closed: the update was in flight and is dropped. */
    DISCARDED
}
