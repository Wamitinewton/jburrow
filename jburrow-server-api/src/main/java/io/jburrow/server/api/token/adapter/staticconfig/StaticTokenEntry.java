package io.jburrow.server.api.token.adapter.staticconfig;

import io.jburrow.server.api.token.domain.AuthtokenHash;
import java.util.Objects;
import java.util.regex.Pattern;

/** One {@code jburrow.auth.static-tokens} entry: a principal name and the hash of that principal's token. */
public record StaticTokenEntry(String name, AuthtokenHash hash) {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");

    public StaticTokenEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hash, "hash");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "a static authtoken name must be 1 to 32 lower-case letters, digits or '-', starting with a"
                            + " letter or digit");
        }
    }

    /**
     * Parses the {@code name:sha256hex} form. The message of a refusal never repeats the entry, in case a token was
     * pasted where its hash belongs.
     *
     * @throws IllegalArgumentException if {@code entry} isn't a valid name and hash separated by one {@code :}
     */
    public static StaticTokenEntry parse(String entry) {
        Objects.requireNonNull(entry, "entry");
        int colon = entry.indexOf(':');
        if (colon < 0 || colon != entry.lastIndexOf(':')) {
            throw new IllegalArgumentException("a static authtoken entry must have the form name:sha256hex");
        }
        return new StaticTokenEntry(entry.substring(0, colon), AuthtokenHash.fromHex(entry.substring(colon + 1)));
    }
}
