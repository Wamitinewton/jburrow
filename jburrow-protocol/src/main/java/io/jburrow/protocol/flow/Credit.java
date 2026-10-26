package io.jburrow.protocol.flow;

/**
 * The {@code WINDOW_UPDATE}s to send after a release: {@code session} on stream 0, {@code stream} on the stream the
 * bytes belonged to. Zero means no update.
 */
public record Credit(int session, int stream) {

    public static final Credit NONE = new Credit(0, 0);

    public Credit {
        if (session < 0 || stream < 0) {
            throw new IllegalArgumentException("Credit can't be negative: session " + session + ", stream " + stream);
        }
    }

    /** {@link #NONE} when both are zero, which is most releases, so the byte path doesn't allocate for them. */
    static Credit of(int session, int stream) {
        return session == 0 && stream == 0 ? NONE : new Credit(session, stream);
    }

    public boolean isEmpty() {
        return session == 0 && stream == 0;
    }
}
