package io.jburrow.server.api.instance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuildIdentityTest {

    @Test
    void keepsItsValues() {
        BuildIdentity identity = new BuildIdentity("0.1.0", "abc1234", List.of("prod"));

        assertThat(identity.version()).isEqualTo("0.1.0");
        assertThat(identity.commit()).isEqualTo("abc1234");
        assertThat(identity.profiles()).containsExactly("prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rejectsBlankVersionAndCommit(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BuildIdentity(blank, "abc1234", List.of("prod")))
                .withMessageContaining("version");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BuildIdentity("0.1.0", blank, List.of("prod")))
                .withMessageContaining("commit");
    }

    @Test
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new BuildIdentity(null, "abc1234", List.of("prod")));
        assertThatNullPointerException().isThrownBy(() -> new BuildIdentity("0.1.0", null, List.of("prod")));
        assertThatNullPointerException().isThrownBy(() -> new BuildIdentity("0.1.0", "abc1234", null));
    }

    @Test
    void rejectsEmptyBlankOrCommaProfiles() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BuildIdentity("0.1.0", "abc1234", List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BuildIdentity("0.1.0", "abc1234", List.of("prod", " ")));
        assertThatIllegalArgumentException().isThrownBy(() -> new BuildIdentity("0.1.0", "abc1234", List.of("a,b")));
    }

    @Test
    void profilesAreCopied() {
        List<String> profiles = new ArrayList<>(List.of("local"));
        BuildIdentity identity = new BuildIdentity("0.1.0", "abc1234", profiles);

        profiles.add("prod");

        assertThat(identity.profiles()).containsExactly("local");
    }
}
