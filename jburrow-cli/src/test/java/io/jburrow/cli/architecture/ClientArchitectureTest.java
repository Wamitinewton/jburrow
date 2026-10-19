package io.jburrow.cli.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The client-side counterpart of the server's {@code JBurrowArchitectureRules}. This module can't see the server's
 * test classes, so the protocol model predicate is a copy of the server's.
 */
class ClientArchitectureTest {

    static final DescribedPredicate<JavaClass> PROTOCOL_MODEL = resideInAPackage("io.jburrow.protocol..")
            .and(not(resideInAPackage("io.jburrow.protocol..adapter..")))
            .as("are in the protocol model under io.jburrow.protocol");

    static final ArchRule CLIENT_NEVER_SEES_SERVER = noClasses()
            .that()
            .resideInAnyPackage("io.jburrow.client..", "io.jburrow.cli..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.jburrow.server..")
            .as("CLIENT_NEVER_SEES_SERVER: client and CLI classes never depend on server classes")
            .allowEmptyShould(true);

    static final ArchRule DOMAIN_IS_PURE = classes()
            .that()
            .resideInAPackage("io.jburrow..domain..")
            .should()
            .onlyDependOnClassesThat(
                    resideInAnyPackage("java..", "io.jburrow..domain..").or(PROTOCOL_MODEL))
            .as("DOMAIN_IS_PURE: domain classes depend only on the JDK, other domain packages and the protocol model")
            .allowEmptyShould(true);

    static final ArchRule APPLICATION_IS_PURE = classes()
            .that()
            .resideInAPackage("io.jburrow..application..")
            .should()
            .onlyDependOnClassesThat(resideInAnyPackage("java..", "io.jburrow..domain..", "io.jburrow..application..")
                    .or(PROTOCOL_MODEL))
            .as("APPLICATION_IS_PURE: application classes depend only on the JDK, domain and application packages,"
                    + " and the protocol model")
            .allowEmptyShould(true);

    static final ArchRule NETTY_ONLY_IN_ADAPTERS = noClasses()
            .that()
            .resideInAnyPackage("io.jburrow.client..", "io.jburrow.cli..")
            .and()
            .resideOutsideOfPackage("..adapter.netty..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.netty..")
            .as("NETTY_ONLY_IN_ADAPTERS: Netty is used only under adapter.netty")
            .allowEmptyShould(true);

    private static final String FIXTURES = "io.jburrow.cli.architecture.fixture.";

    private static final Pattern TEST_JAR = Pattern.compile(".*-tests\\.jar!/.*");

    /**
     * {@code DO_NOT_INCLUDE_TESTS} recognises {@code target/test-classes}, but once the reactor has packaged a module,
     * its test support reaches downstream modules as a {@code -tests.jar}.
     */
    private static final ImportOption DO_NOT_INCLUDE_TEST_JARS = location -> !location.matches(TEST_JAR);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(DO_NOT_INCLUDE_TEST_JARS)
            .importPackages("io.jburrow.client", "io.jburrow.cli");

    static Stream<Arguments> rules() {
        return Stream.of(
                arguments("CLIENT_NEVER_SEES_SERVER", CLIENT_NEVER_SEES_SERVER, "seesserver"),
                arguments("DOMAIN_IS_PURE", DOMAIN_IS_PURE, "impuredomain"),
                arguments("APPLICATION_IS_PURE", APPLICATION_IS_PURE, "impureapplication"),
                arguments("NETTY_ONLY_IN_ADAPTERS", NETTY_ONLY_IN_ADAPTERS, "nettyleak"));
    }

    static Stream<Arguments> relaxedRules() {
        return Stream.of(
                arguments("DOMAIN_IS_PURE", DOMAIN_IS_PURE), arguments("APPLICATION_IS_PURE", APPLICATION_IS_PURE));
    }

    @ParameterizedTest(name = "{0} holds for the client and CLI modules")
    @MethodSource("rules")
    void ruleHoldsForProductionCode(String name, ArchRule rule, String fixture) {
        rule.check(PRODUCTION_CLASSES);
    }

    @ParameterizedTest(name = "{0} rejects fixture {2}")
    @MethodSource("rules")
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
        JavaClasses fixtureClasses = new ClassFileImporter().importPackages(FIXTURES + "protocolinclient");
        assertThat(fixtureClasses).isNotEmpty();

        rule.check(fixtureClasses);
    }
}
