package io.jburrow.server.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JBurrowArchitectureTest {

    private static final String FIXTURES = "io.jburrow.server.architecture.fixture.";

    private static final Pattern TEST_JAR = Pattern.compile(".*-tests\\.jar!/.*");

    /**
     * {@code DO_NOT_INCLUDE_TESTS} recognises {@code target/test-classes}, but once the reactor has packaged a module,
     * its test support reaches downstream modules as a {@code -tests.jar}.
     */
    private static final ImportOption DO_NOT_INCLUDE_TEST_JARS = location -> !location.matches(TEST_JAR);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(DO_NOT_INCLUDE_TEST_JARS)
            .importPackages("io.jburrow.server", "io.jburrow.protocol");

    static Stream<Arguments> rules() {
        return Stream.of(
                arguments("PROTOCOL_MODEL_IS_PURE", JBurrowArchitectureRules.PROTOCOL_MODEL_IS_PURE),
                arguments("DOMAIN_IS_PURE", JBurrowArchitectureRules.DOMAIN_IS_PURE),
                arguments("APPLICATION_IS_PURE", JBurrowArchitectureRules.APPLICATION_IS_PURE),
                arguments("NETTY_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.NETTY_ONLY_IN_ADAPTERS),
                arguments("WEB_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.WEB_ONLY_IN_ADAPTERS),
                arguments("JPA_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.JPA_ONLY_IN_ADAPTERS),
                arguments("MAIL_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.MAIL_ONLY_IN_ADAPTERS),
                arguments("FEATURES_TALK_VIA_PORTS", JBurrowArchitectureRules.FEATURES_TALK_VIA_PORTS),
                arguments("NO_FEATURE_CYCLES", JBurrowArchitectureRules.NO_FEATURE_CYCLES),
                arguments("NO_SPRING_IN_CORE_LAYERS", JBurrowArchitectureRules.NO_SPRING_IN_CORE_LAYERS),
                arguments("SERVER_NEVER_SEES_CLIENT", JBurrowArchitectureRules.SERVER_NEVER_SEES_CLIENT));
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                arguments(
                        "PROTOCOL_MODEL_IS_PURE",
                        JBurrowArchitectureRules.protocolModelIsPure(FIXTURES + "impureprotocol"),
                        "impureprotocol"),
                arguments("DOMAIN_IS_PURE", JBurrowArchitectureRules.DOMAIN_IS_PURE, "impuredomain"),
                arguments("DOMAIN_IS_PURE", JBurrowArchitectureRules.DOMAIN_IS_PURE, "impuresharederror"),
                arguments("APPLICATION_IS_PURE", JBurrowArchitectureRules.APPLICATION_IS_PURE, "impureapplication"),
                arguments("NETTY_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.NETTY_ONLY_IN_ADAPTERS, "nettyleak"),
                arguments("WEB_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.WEB_ONLY_IN_ADAPTERS, "webleak"),
                arguments("JPA_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.JPA_ONLY_IN_ADAPTERS, "jpaleak.jakarta"),
                arguments("JPA_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.JPA_ONLY_IN_ADAPTERS, "jpaleak.springdata"),
                arguments("MAIL_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.MAIL_ONLY_IN_ADAPTERS, "mailleak.jakarta"),
                arguments("MAIL_ONLY_IN_ADAPTERS", JBurrowArchitectureRules.MAIL_ONLY_IN_ADAPTERS, "mailleak.spring"),
                arguments("FEATURES_TALK_VIA_PORTS", JBurrowArchitectureRules.FEATURES_TALK_VIA_PORTS, "crossfeature"),
                arguments("NO_FEATURE_CYCLES", JBurrowArchitectureRules.NO_FEATURE_CYCLES, "cycle"),
                arguments(
                        "NO_SPRING_IN_CORE_LAYERS",
                        JBurrowArchitectureRules.NO_SPRING_IN_CORE_LAYERS,
                        "springincore.component"),
                arguments(
                        "NO_SPRING_IN_CORE_LAYERS",
                        JBurrowArchitectureRules.NO_SPRING_IN_CORE_LAYERS,
                        "springincore.service"),
                arguments(
                        "NO_SPRING_IN_CORE_LAYERS",
                        JBurrowArchitectureRules.NO_SPRING_IN_CORE_LAYERS,
                        "springincore.transactional"),
                arguments(
                        "NO_SPRING_IN_CORE_LAYERS",
                        JBurrowArchitectureRules.NO_SPRING_IN_CORE_LAYERS,
                        "springincore.autowired"),
                arguments("SERVER_NEVER_SEES_CLIENT", JBurrowArchitectureRules.SERVER_NEVER_SEES_CLIENT, "seesclient"));
    }

    @ParameterizedTest(name = "{0} holds for the server and protocol modules")
    @MethodSource("rules")
    void ruleHoldsForProductionCode(String name, ArchRule rule) {
        rule.check(PRODUCTION_CLASSES);
    }

    @ParameterizedTest(name = "{0} rejects fixture {2}")
    @MethodSource("violations")
    void ruleRejectsItsFixture(String name, ArchRule rule, String fixture) {
        JavaClasses fixtureClasses = new ClassFileImporter().importPackages(FIXTURES + fixture);
        assertThat(fixtureClasses).isNotEmpty();

        assertThatThrownBy(() -> rule.check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(name);
    }

    @ParameterizedTest(name = "{0} accepts the protocol model")
    @MethodSource("relaxedRules")
    void coreLayersMayUseTheProtocolModel(String name, ArchRule rule) {
        JavaClasses fixtureClasses = new ClassFileImporter().importPackages(FIXTURES + "protocolincore");
        assertThat(fixtureClasses).isNotEmpty();

        rule.check(fixtureClasses);
    }

    static Stream<Arguments> relaxedRules() {
        return Stream.of(
                arguments("DOMAIN_IS_PURE", JBurrowArchitectureRules.DOMAIN_IS_PURE),
                arguments("APPLICATION_IS_PURE", JBurrowArchitectureRules.APPLICATION_IS_PURE));
    }

    @Test
    void protocolAdaptersAreOutsideTheModel() {
        String root = FIXTURES + "impureprotocol";
        JavaClasses adapterClasses = new ClassFileImporter().importPackages(root + ".adapter");
        assertThat(adapterClasses).isNotEmpty();

        JBurrowArchitectureRules.protocolModelIsPure(root).check(adapterClasses);
    }

    @Test
    void everyRuleHasAFixture() {
        assertThat(violations().map(arguments -> arguments.get()[0]).distinct())
                .containsExactlyInAnyOrderElementsOf(
                        rules().map(arguments -> arguments.get()[0]).toList());
    }

    @Test
    void featureIsThePackageAboveTheLayer() {
        assertThat(JBurrowArchitectureRules.featureOf("io.jburrow.server.api.instance.adapter.web"))
                .contains("io.jburrow.server.api.instance");
        assertThat(JBurrowArchitectureRules.featureOf("io.jburrow.server.api.shared.web"))
                .isEmpty();
        assertThat(JBurrowArchitectureRules.featureOf("org.springframework.data.domain"))
                .isEmpty();
        assertThat(JBurrowArchitectureRules.featureOf("io.jburrow.protocol.adapter.netty"))
                .isEmpty();
    }

    @Test
    void engineAdaptersMayUseTheProtocolAdapters() {
        JavaClasses fixtureClasses = new ClassFileImporter().importPackages(FIXTURES + "protocoladapterinengine");
        assertThat(fixtureClasses).isNotEmpty();

        JBurrowArchitectureRules.FEATURES_TALK_VIA_PORTS.check(fixtureClasses);
        JBurrowArchitectureRules.NO_FEATURE_CYCLES.check(fixtureClasses);
    }
}
