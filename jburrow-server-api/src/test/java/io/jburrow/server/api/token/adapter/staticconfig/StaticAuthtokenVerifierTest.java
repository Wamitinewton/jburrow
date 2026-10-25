package io.jburrow.server.api.token.adapter.staticconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.server.api.token.domain.Authtoken;
import io.jburrow.server.api.token.domain.AuthtokenHash;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StaticAuthtokenVerifierTest {

    private static final String ALICE_TOKEN = "jb_" + "a".repeat(43);
    private static final String BOB_TOKEN = "jb_" + "b".repeat(43);
    private static final String CAROL_TOKEN = "jb_" + "c".repeat(43);
    private static final String UNKNOWN_TOKEN = "jb_" + "z".repeat(43);

    private static final List<StaticTokenEntry> ENTRIES =
            List.of(entry("alice", ALICE_TOKEN), entry("bob", BOB_TOKEN), entry("carol", CAROL_TOKEN));

    private final AtomicInteger comparisons = new AtomicInteger();
    private final StaticAuthtokenVerifier verifier = new StaticAuthtokenVerifier(ENTRIES, (configured, presented) -> {
        comparisons.incrementAndGet();
        return configured.matches(presented);
    });

    @Test
    void aKnownTokenIsItsEntrysName() {
        assertThat(new StaticAuthtokenVerifier(ENTRIES).verify(BOB_TOKEN)).contains("bob");
    }

    @Test
    void anUnknownTokenIsRefused() {
        assertThat(verifier.verify(UNKNOWN_TOKEN)).isEmpty();
    }

    @Test
    void aMalformedTokenIsRefusedWithoutComparing() {
        assertThat(verifier.verify("not-a-token")).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
        assertThat(comparisons).hasValue(0);
    }

    @Test
    void aMatchOnTheFirstEntryStillComparesEveryEntry() {
        assertThat(verifier.verify(ALICE_TOKEN)).contains("alice");
        assertThat(comparisons).hasValue(3);
    }

    @Test
    void aMatchOnTheLastEntryComparesEveryEntry() {
        assertThat(verifier.verify(CAROL_TOKEN)).contains("carol");
        assertThat(comparisons).hasValue(3);
    }

    @Test
    void noMatchComparesEveryEntry() {
        assertThat(verifier.verify(UNKNOWN_TOKEN)).isEmpty();
        assertThat(comparisons).hasValue(3);
    }

    @Test
    void withNoEntriesEveryTokenIsRefused() {
        StaticAuthtokenVerifier empty = new StaticAuthtokenVerifier(List.of());

        assertThat(empty.verify(ALICE_TOKEN)).isEmpty();
        assertThat(empty.size()).isZero();
    }

    @Test
    void duplicateNamesAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new StaticAuthtokenVerifier(List.of(entry("alice", ALICE_TOKEN), entry("alice", BOB_TOKEN))))
                .withMessageContaining("alice");
    }

    @Test
    void duplicateHashesAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new StaticAuthtokenVerifier(List.of(entry("alice", ALICE_TOKEN), entry("bob", ALICE_TOKEN))))
                .withMessageContaining("bob");
    }

    private static StaticTokenEntry entry(String name, String token) {
        return new StaticTokenEntry(
                name, AuthtokenHash.of(Authtoken.parse(token).orElseThrow()));
    }
}
