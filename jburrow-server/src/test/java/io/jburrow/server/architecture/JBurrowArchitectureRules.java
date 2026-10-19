package io.jburrow.server.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The layer rules from {@code docs/phase-0-foundation/ARCHITECTURE.md}, as changed by
 * {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Layer rules, applied to every server and protocol class.
 *
 * <p>The protocol model is {@code io.jburrow.protocol} outside its {@code adapter} packages: pure Java, and the one
 * thing besides the JDK that domain and application code may depend on across modules (ADR-0007).
 *
 * <p>A feature is the package that directly contains a {@code domain}, {@code application} or {@code adapter}
 * package, for example {@code io.jburrow.server.api.instance}. Packages without a layer, such as {@code shared} or
 * the composition root's {@code config}, belong to no feature.
 */
public final class JBurrowArchitectureRules {

    private static final Set<String> LAYERS = Set.of("domain", "application", "adapter");

    private static final Set<String> SPRING_CORE_LAYER_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional",
            "org.springframework.beans.factory.annotation.Autowired");

    private static final String PROTOCOL_ROOT = "io.jburrow.protocol";

    public static final DescribedPredicate<JavaClass> PROTOCOL_MODEL = protocolModel(PROTOCOL_ROOT);

    public static final ArchRule PROTOCOL_MODEL_IS_PURE = protocolModelIsPure(PROTOCOL_ROOT);

    public static final ArchRule DOMAIN_IS_PURE = classes()
            .that()
            .resideInAnyPackage("io.jburrow..domain..", "io.jburrow..shared.error..")
            .should()
            .onlyDependOnClassesThat(resideInAnyPackage("java..", "io.jburrow..domain..", "io.jburrow..shared.error..")
                    .or(PROTOCOL_MODEL))
            .as("DOMAIN_IS_PURE: domain and shared error classes depend only on the JDK, domain packages, the"
                    + " shared error types and the protocol model")
            .allowEmptyShould(true);

    public static final ArchRule APPLICATION_IS_PURE = classes()
            .that()
            .resideInAPackage("io.jburrow..application..")
            .should()
            .onlyDependOnClassesThat(resideInAnyPackage(
                            "java..", "io.jburrow..domain..", "io.jburrow..application..", "io.jburrow..shared.error..")
                    .or(PROTOCOL_MODEL))
            .as("APPLICATION_IS_PURE: application classes depend only on the JDK, domain and application packages,"
                    + " the shared error types and the protocol model")
            .allowEmptyShould(true);

    public static final ArchRule NETTY_ONLY_IN_ADAPTERS = noClasses()
            .that()
            .resideOutsideOfPackage("..adapter.netty..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.netty..")
            .as("NETTY_ONLY_IN_ADAPTERS: Netty is used only under adapter.netty")
            .allowEmptyShould(true);

    public static final ArchRule WEB_ONLY_IN_ADAPTERS = noClasses()
            .that()
            .resideOutsideOfPackages("..adapter.web..", "..shared.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.web..")
            .as("WEB_ONLY_IN_ADAPTERS: Spring Web is used only under adapter.web and shared.web")
            .allowEmptyShould(true);

    public static final ArchRule JPA_ONLY_IN_ADAPTERS = noClasses()
            .that()
            .resideOutsideOfPackage("..adapter.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
            .as("JPA_ONLY_IN_ADAPTERS: JPA and Spring Data are used only under adapter.persistence")
            .allowEmptyShould(true);

    public static final ArchRule MAIL_ONLY_IN_ADAPTERS = noClasses()
            .that()
            .resideOutsideOfPackage("..adapter.smtp..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.mail..", "org.springframework.mail..")
            .as("MAIL_ONLY_IN_ADAPTERS: Jakarta Mail and Spring Mail are used only under adapter.smtp")
            .allowEmptyShould(true);

    public static final ArchRule FEATURES_TALK_VIA_PORTS = classes()
            .that()
            .resideInAPackage("io.jburrow..")
            .should(reachOtherFeaturesOnlyThroughPorts())
            .as("FEATURES_TALK_VIA_PORTS: a feature reaches another feature only through its application.port package")
            .allowEmptyShould(true);

    public static final ArchRule NO_FEATURE_CYCLES = slices().assignedFrom(features())
            .should()
            .beFreeOfCycles()
            .as("NO_FEATURE_CYCLES: features are free of dependency cycles")
            .allowEmptyShould(true);

    public static final ArchRule NO_SPRING_IN_CORE_LAYERS = noClasses()
            .that()
            .resideInAnyPackage("io.jburrow..domain..", "io.jburrow..application..")
            .should()
            .dependOnClassesThat(DescribedPredicate.describe(
                    "are Spring stereotype, injection or transaction annotations",
                    target -> SPRING_CORE_LAYER_ANNOTATIONS.contains(target.getName())))
            .as("NO_SPRING_IN_CORE_LAYERS: no @Component, @Service, @Transactional or @Autowired under domain or"
                    + " application")
            .allowEmptyShould(true);

    public static final ArchRule SERVER_NEVER_SEES_CLIENT = noClasses()
            .that()
            .resideInAnyPackage("io.jburrow.server..", "io.jburrow.protocol..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.jburrow.client..", "io.jburrow.cli..")
            .as("SERVER_NEVER_SEES_CLIENT: server and protocol classes never depend on client classes")
            .allowEmptyShould(true);

    private JBurrowArchitectureRules() {}

    /** The classes under {@code root}, outside any {@code adapter} package below it. */
    static DescribedPredicate<JavaClass> protocolModel(String root) {
        return resideInAPackage(root + "..")
                .and(not(resideInAPackage(root + "..adapter..")))
                .as("are in the protocol model under " + root);
    }

    /**
     * {@code PROTOCOL_MODEL_IS_PURE} for the protocol model under {@code root}. The root is a parameter only so the
     * architecture test can apply the same rule to a fixture package.
     */
    static ArchRule protocolModelIsPure(String root) {
        return classes()
                .that(protocolModel(root))
                .should()
                .onlyDependOnClassesThat(resideInAPackage("java..").or(protocolModel(root)))
                .as("PROTOCOL_MODEL_IS_PURE: the protocol model depends only on the JDK and itself; Netty and Jackson"
                        + " stay in its adapter packages")
                .allowEmptyShould(true);
    }

    static Optional<String> featureOf(String packageName) {
        if (!packageName.startsWith("io.jburrow.")) {
            return Optional.empty();
        }
        String[] segments = packageName.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (LAYERS.contains(segments[i])) {
                return Optional.of(String.join(".", Arrays.copyOf(segments, i)));
            }
        }
        return Optional.empty();
    }

    private static ArchCondition<JavaClass> reachOtherFeaturesOnlyThroughPorts() {
        return new ArchCondition<>("reach other features only through their application.port package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originFeature = featureOf(origin.getPackageName());
                if (originFeature.isEmpty()) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    Optional<String> targetFeature = featureOf(target.getPackageName());
                    if (targetFeature.isPresent()
                            && !targetFeature.equals(originFeature)
                            && !target.getPackageName().startsWith(targetFeature.get() + ".application.port")) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static SliceAssignment features() {
        return new SliceAssignment() {
            @Override
            public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                return featureOf(javaClass.getPackageName())
                        .map(SliceIdentifier::of)
                        .orElse(SliceIdentifier.ignore());
            }

            @Override
            public String getDescription() {
                return "features";
            }
        };
    }
}
