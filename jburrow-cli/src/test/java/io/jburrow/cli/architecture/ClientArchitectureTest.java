package io.jburrow.cli.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The client-side counterpart of the server's {@code JBurrowArchitectureRules}. */
class ClientArchitectureTest {

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
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "io.jburrow..domain..")
            .as("DOMAIN_IS_PURE: domain classes depend only on the JDK and other domain packages")
            .allowEmptyShould(true);

    static final ArchRule APPLICATION_IS_PURE = classes()
            .that()
            .resideInAPackage("io.jburrow..application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "io.jburrow..domain..", "io.jburrow..application..")
            .as("APPLICATION_IS_PURE: application classes depend only on the JDK, domain and application packages")
            .allowEmptyShould(true);

    private static final String FIXTURES = "io.jburrow.cli.architecture.fixture.";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.jburrow.client", "io.jburrow.cli");

    static Stream<Arguments> rules() {
        return Stream.of(
                arguments("CLIENT_NEVER_SEES_SERVER", CLIENT_NEVER_SEES_SERVER, "seesserver"),
                arguments("DOMAIN_IS_PURE", DOMAIN_IS_PURE, "impuredomain"),
                arguments("APPLICATION_IS_PURE", APPLICATION_IS_PURE, "impureapplication"));
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
}
