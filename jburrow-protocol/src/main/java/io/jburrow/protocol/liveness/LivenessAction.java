package io.jburrow.protocol.liveness;

/** What {@link Liveness#check} asks the caller to do. */
public enum LivenessAction {
    NONE,
    /** Send a {@code PING}, then report it with {@link Liveness#onPingSent}. */
    SEND_PING,
    /** Nothing arrived for the idle timeout: send {@code ERROR HEARTBEAT_TIMEOUT} best effort and close. */
    DEAD
}
