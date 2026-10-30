package io.jburrow.server.core.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.SubdomainName;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NameGeneratorTest {

    @Test
    void namesAreAnAdjectiveAnAnimalAndFourHexDigits() {
        NameGenerator generator = new NameGenerator(new Random(42));

        for (int i = 0; i < 1_000; i++) {
            String[] parts = generator.next().value().split("-");

            assertThat(parts).hasSize(3);
            assertThat(Words.ADJECTIVES).contains(parts[0]);
            assertThat(Words.ANIMALS).contains(parts[1]);
            assertThat(parts[2]).matches("[0-9a-f]{4}");
        }
    }

    @Test
    void theSameSeedDrawsTheSameNames() {
        NameGenerator first = new NameGenerator(new Random(7));
        NameGenerator second = new NameGenerator(new Random(7));

        for (int i = 0; i < 10; i++) {
            assertThat(first.next()).isEqualTo(second.next());
        }
    }

    @Test
    void drawsSpreadAcrossTheSpace() {
        NameGenerator generator = new NameGenerator(new Random(1));
        Set<SubdomainName> drawn = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            drawn.add(generator.next());
        }

        assertThat(drawn).hasSizeGreaterThan(9_990);
    }

    @Test
    void theSmallestAndLargestDrawsAreValid() {
        assertThat(new NameGenerator(() -> 0L).next()).isEqualTo(new SubdomainName("able-adder-0000"));
        assertThat(new NameGenerator(() -> -1L).next()).isEqualTo(new SubdomainName("zesty-zebu-ffff"));
    }
}
