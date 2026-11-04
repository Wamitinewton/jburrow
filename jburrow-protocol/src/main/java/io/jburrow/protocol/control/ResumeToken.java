package io.jburrow.protocol.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * A resume token, with {@code resume}: names the live session it was issued to, so a newer session of the same client
 * can end it (§7.6). {@code jbr_} and 43 characters of unpadded base64url, 32 random bytes in all. Not a credential:
 * it is honoured only next to the authtoken of the same owner.
 *
 * <p>{@link #toString()} never contains the token.
 */
public record ResumeToken(String value) {

    static final String PREFIX = "jbr_";

    private static final int RANDOM_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public ResumeToken {
        Fields.matching(value, Fields.RESUME_TOKEN, "resumeToken");
    }

    /** A new token of 32 bytes from {@code random}; the server passes a {@code SecureRandom}. */
    public static ResumeToken generate(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return new ResumeToken(PREFIX + ENCODER.encodeToString(bytes));
    }

    /** The SHA-256 of the token's text: what a server keeps in place of the token. */
    public byte[] sha256() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }

    @Override
    public String toString() {
        return "ResumeToken[" + PREFIX + "***]";
    }
}
