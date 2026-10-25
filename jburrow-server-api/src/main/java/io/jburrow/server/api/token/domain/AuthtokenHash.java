package io.jburrow.server.api.token.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The SHA-256 of an authtoken's full text, prefix included. A slow hash isn't needed for a 256-bit random secret.
 *
 * <p>Neither a record nor overriding {@code equals} and {@code hashCode}: hashes are compared only through
 * {@link #matches}, in constant time, so a presented token's hash can't end up as a key in a hash map, whose lookup
 * short-circuits and leaks timing.
 */
public final class AuthtokenHash {

    private static final int BYTES = 32;
    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final byte[] bytes;

    private AuthtokenHash(byte[] bytes) {
        this.bytes = bytes;
    }

    public static AuthtokenHash of(Authtoken token) {
        Objects.requireNonNull(token, "token");
        return new AuthtokenHash(sha256().digest(token.value().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * @param hex exactly 64 lower-case hexadecimal characters
     * @throws IllegalArgumentException if {@code hex} has any other shape
     */
    public static AuthtokenHash fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        if (!HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException(
                    "an authtoken hash must be " + BYTES * 2 + " lower-case hexadecimal characters");
        }
        return new AuthtokenHash(HexFormat.of().parseHex(hex));
    }

    /** Whether both hashes are equal, in time that doesn't depend on where they differ. */
    public boolean matches(AuthtokenHash other) {
        Objects.requireNonNull(other, "other");
        return MessageDigest.isEqual(bytes, other.bytes);
    }

    public String toHex() {
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }
}
