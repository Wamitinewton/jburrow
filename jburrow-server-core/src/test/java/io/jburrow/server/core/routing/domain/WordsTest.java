package io.jburrow.server.core.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.SubdomainName;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WordsTest {

    @Test
    void eachListHas256SortedUniqueShortWords() {
        for (List<String> words : List.of(Words.ADJECTIVES, Words.ANIMALS)) {
            assertThat(words)
                    .hasSize(256)
                    .isSorted()
                    .doesNotHaveDuplicates()
                    .allMatch(word -> word.matches("[a-z]{3,7}"));
        }
    }

    @Test
    void noWordAndNoJoinIsBlocked() {
        Blocklist blocklist = Blocklist.defaults(Set.of());
        List<String> refused = new ArrayList<>();
        for (String adjective : Words.ADJECTIVES) {
            for (String animal : Words.ANIMALS) {
                String join = adjective + "-" + animal;
                if (!blocklist.allows(new SubdomainName(join))) {
                    refused.add(join);
                }
            }
        }
        for (String word : concat(Words.ADJECTIVES, Words.ANIMALS)) {
            if (!blocklist.allows(new SubdomainName(word))) {
                refused.add(word);
            }
        }

        assertThat(refused).isEmpty();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
