package io.jburrow.server.core.routing.domain;

import io.jburrow.protocol.control.SubdomainName;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Draws random tunnel names, {@code <adjective>-<animal>-<4 hex digits>}: 2^32 of them. */
public final class NameGenerator {

    private static final HexFormat HEX = HexFormat.of();

    private final RandomGenerator random;

    public NameGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public SubdomainName next() {
        String adjective = pick(Words.ADJECTIVES);
        String animal = pick(Words.ANIMALS);
        String suffix = HEX.toHexDigits((short) random.nextInt(1 << 16));
        return new SubdomainName(adjective + "-" + animal + "-" + suffix);
    }

    private String pick(List<String> words) {
        return words.get(random.nextInt(words.size()));
    }
}
