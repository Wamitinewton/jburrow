package io.jburrow.server.api.token.adapter.staticconfig;

import io.jburrow.server.api.token.domain.Authtoken;
import io.jburrow.server.api.token.domain.AuthtokenHash;
import io.jburrow.server.core.tunnel.application.port.AuthtokenVerifier;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 1's {@link AuthtokenVerifier}: a fixed list of named token hashes from configuration.
 *
 * <p>A presented token is hashed once and compared with every entry, without stopping at a match, so the time taken
 * doesn't reveal which entry matched or whether any did. Phase 5 replaces this with a Postgres-backed verifier.
 */
public final class StaticAuthtokenVerifier implements AuthtokenVerifier {

    /** The comparison of two hashes, a seam so tests can count comparisons. */
    @FunctionalInterface
    interface HashComparison {
        boolean matches(AuthtokenHash configured, AuthtokenHash presented);
    }

    private final List<StaticTokenEntry> entries;
    private final HashComparison comparison;

    /** @throws IllegalArgumentException if two entries share a name or a hash */
    public StaticAuthtokenVerifier(List<StaticTokenEntry> entries) {
        this(entries, AuthtokenHash::matches);
    }

    StaticAuthtokenVerifier(List<StaticTokenEntry> entries, HashComparison comparison) {
        this.entries = List.copyOf(entries);
        this.comparison = Objects.requireNonNull(comparison, "comparison");
        rejectDuplicates(this.entries);
    }

    @Override
    public Optional<String> verify(String authtoken) {
        Optional<Authtoken> token = Authtoken.parse(authtoken);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        AuthtokenHash presented = token.get().hash();
        StaticTokenEntry matched = null;
        for (StaticTokenEntry entry : entries) {
            if (comparison.matches(entry.hash(), presented)) {
                matched = entry;
            }
        }
        return Optional.ofNullable(matched).map(StaticTokenEntry::name);
    }

    public int size() {
        return entries.size();
    }

    /** Configured hashes aren't secret, so a set of their hex is fine here, unlike for a presented token's hash. */
    private static void rejectDuplicates(List<StaticTokenEntry> entries) {
        Set<String> names = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (StaticTokenEntry entry : entries) {
            if (!names.add(entry.name())) {
                throw new IllegalArgumentException("the static authtoken name '" + entry.name() + "' is used twice");
            }
            if (!hashes.add(entry.hash().toHex())) {
                throw new IllegalArgumentException(
                        "the static authtoken '" + entry.name() + "' has the same hash as an earlier entry");
            }
        }
    }
}
