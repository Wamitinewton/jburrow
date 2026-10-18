package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.domain.BuildIdentity;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

class BuildIdentityConfigurationTest {

    private final BuildIdentityConfiguration configuration = new BuildIdentityConfiguration();

    @Test
    void readsVersionAndCommitFromBuildInfo() {
        BuildIdentity identity = identityFrom(buildProperties("0.1.0", "abc1234"));

        assertThat(identity.version()).isEqualTo("0.1.0");
        assertThat(identity.commit()).isEqualTo("abc1234");
        assertThat(identity.profiles()).containsExactly("default");
    }

    @Test
    void anUnresolvedCommitExpressionIsUnknown() {
        BuildIdentity identity = identityFrom(buildProperties("0.1.0", "${git.commit.id.abbrev}"));

        assertThat(identity.commit()).isEqualTo(BuildIdentityConfiguration.UNKNOWN);
    }

    @Test
    void withoutBuildInfoBothAreUnknown() {
        BuildIdentity identity = identityFrom(new StaticListableBeanFactory());

        assertThat(identity.version()).isEqualTo(BuildIdentityConfiguration.UNKNOWN);
        assertThat(identity.commit()).isEqualTo(BuildIdentityConfiguration.UNKNOWN);
    }

    private BuildIdentity identityFrom(StaticListableBeanFactory beans) {
        return configuration
                .buildIdentitySource(beans.getBeanProvider(BuildProperties.class), new MockEnvironment())
                .current();
    }

    private static StaticListableBeanFactory buildProperties(String version, String commit) {
        Properties entries = new Properties();
        entries.setProperty("version", version);
        entries.setProperty("commit", commit);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("buildProperties", new BuildProperties(entries));
        return beans;
    }
}
