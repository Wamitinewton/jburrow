package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.control.ResumeToken;
import io.jburrow.server.core.tunnel.domain.ResumeTokenHash;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Issues resume tokens ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Resume tokens). Thread-safe. */
public final class ResumeTokens {

    /**
     * A token just issued and its hash. The token goes into one {@code REGISTER} reply and nowhere else; the session
     * keeps the hash.
     */
    public record Issued(ResumeToken token, ResumeTokenHash hash) {

        public Issued {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(hash, "hash");
        }
    }

    private final RandomGenerator random;

    /** Tokens from a new {@link SecureRandom}. */
    public ResumeTokens() {
        this(new SecureRandom());
    }

    /** @param random a {@link SecureRandom} outside tests, which must also be thread-safe */
    public ResumeTokens(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public Issued issue() {
        ResumeToken token = ResumeToken.generate(random);
        return new Issued(token, ResumeTokenHash.of(token));
    }
}
