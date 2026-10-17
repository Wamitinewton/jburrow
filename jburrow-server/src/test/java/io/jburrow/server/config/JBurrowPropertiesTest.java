package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class JBurrowPropertiesTest {

    private static final Map<String, String> DEFAULTS = Map.of(
            "jburrow.api.prefix", "/api/v1",
            "jburrow.mail.from-address", "no-reply@jburrow.localhost",
            "jburrow.mail.from-name", "JBurrow");

    @Test
    void defaultsBind() {
        JBurrowProperties properties = bind(DEFAULTS);

        assertThat(properties.api().prefix()).isEqualTo("/api/v1");
        assertThat(properties.mail().fromAddress()).isEqualTo("no-reply@jburrow.localhost");
        assertThat(properties.mail().fromName()).isEqualTo("JBurrow");
        assertThat(properties.mail().sesConfigurationSet()).isNull();
        assertThat(properties.mail().smokeTestRecipient()).isNull();
    }

    @Test
    void emptyOptionalMailSettingsBind() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.mail.ses-configuration-set", "", "jburrow.mail.smoke-test-recipient", "")));

        assertThat(properties.mail().smokeTestRecipient()).isEmpty();
    }

    @Test
    void blankFromAddressFailsNamingTheKey() {
        assertThatThrownBy(() -> bind(with(Map.of("jburrow.mail.from-address", " "))))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("jburrow.mail.from-address");
    }

    @Test
    void invalidSmokeTestRecipientFailsNamingTheKey() {
        assertThatThrownBy(() -> bind(with(Map.of("jburrow.mail.smoke-test-recipient", "not-an-address"))))
                .rootCause()
                .hasMessageContaining("jburrow.mail.smoke-test-recipient");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api/v1", "/api/v1/", "/", ""})
    void malformedPrefixFailsNamingTheKey(String prefix) {
        assertThatThrownBy(() -> bind(with(Map.of("jburrow.api.prefix", prefix))))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("jburrow.api.prefix");
    }

    private static Map<String, String> with(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        values.putAll(overrides);
        return values;
    }

    private static JBurrowProperties bind(Map<String, String> values) {
        try (LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            return new Binder(new MapConfigurationPropertySource(values))
                    .bindOrCreate(
                            "jburrow", Bindable.of(JBurrowProperties.class), new ValidationBindHandler(validator));
        }
    }
}
