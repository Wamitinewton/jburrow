package io.jburrow.server.api.token.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A presented authtoken: {@code jb_} followed by 43 characters of unpadded base64url, the encoding of 32 random bytes.
 *
 * <p>Deliberately not a record and without {@code equals}: tokens are compared only through their
 * {@link AuthtokenHash}, in constant time. {@link #toString()} never shows the secret.
 */
public final class Authtoken {

    public static final String PREFIX = "jb_";
    public static final int LENGTH = 46;

    private static final Pattern FORMAT = Pattern.compile("^jb_[A-Za-z0-9_-]{43}$");

    private final String value;

    private Authtoken(String value) {
        this.value = value;
    }

    /** The token, or empty if {@code raw} is null or not exactly the token format. Never hashes anything. */
    public static Optional<Authtoken> parse(String raw) {
        if (raw == null || raw.length() != LENGTH || !FORMAT.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(new Authtoken(raw));
    }

    public AuthtokenHash hash() {
        return AuthtokenHash.of(this);
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return PREFIX + "***";
    }
}
