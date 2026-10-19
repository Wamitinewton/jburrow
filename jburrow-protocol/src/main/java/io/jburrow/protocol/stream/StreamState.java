package io.jburrow.protocol.stream;

/** Where one stream is, seen from this side. "Local" is this side, "remote" the peer. */
public enum StreamState {
    OPEN,
    /** This side sent {@code CLOSE}; the peer may still send. */
    HALF_CLOSED_LOCAL,
    /** The peer sent {@code CLOSE}; this side may still send. */
    HALF_CLOSED_REMOTE,
    CLOSED
}
