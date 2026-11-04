package io.jburrow.server.core.tunnel.domain;

import io.jburrow.protocol.control.ResumeToken;
import java.util.Arrays;
import java.util.Objects;

/**
 * The SHA-256 of a resume token: all the server keeps of one, in its session and in the index of live sessions. Equal
 * by content, so it can key a map.
 *
 * <p>{@link #toString()} never contains the hash.
 */
public final class ResumeTokenHash {

    private static final int LENGTH = 32;

    private final byte[] value;

    private ResumeTokenHash(byte[] value) {
        this.value = value;
    }

    public static ResumeTokenHash of(ResumeToken token) {
        return new ResumeTokenHash(Objects.requireNonNull(token, "token").sha256());
    }

    /** @throws IllegalArgumentException unless {@code sha256} is 32 bytes */
    public static ResumeTokenHash of(byte[] sha256) {
        Objects.requireNonNull(sha256, "sha256");
        if (sha256.length != LENGTH) {
            throw new IllegalArgumentException("A resume token hash is " + LENGTH + " bytes, not " + sha256.length);
        }
        return new ResumeTokenHash(sha256.clone());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResumeTokenHash hash && Arrays.equals(value, hash.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "ResumeTokenHash[***]";
    }
}
