package com.acme.platform.build.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

// Bytecode-level half of the constitution: rules the POM-level PlatformLayerRule cannot see
// (package boundaries, injection style, cycles). Every module runs these via ArchConstitutionTest.
public final class PlatformArchRules {

    private static final String PLATFORM_ROOT = "com.acme.platform";
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.AutoConfiguration";
    private static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    private PlatformArchRules() {
    }

    /** The full constitution; modules run every rule over their own production classes. */
    public static ArchRule[] all() {
        return new ArchRule[] {
            internalPackagesAreModulePrivate(),
            apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore(),
            noFieldInjection(),
            noCapabilityCycles(),
            autoConfigurationsAreAnnotatedAndPlaced(),
            configurationPropertiesAreRecords(),
        };
    }

    /**
     * A class may use {@code x.y.internal.*} only if it lives under {@code x.y} itself —
     * approximates "no dependency on ANOTHER module's internals" at bytecode level.
     */
    static ArchRule internalPackagesAreModulePrivate() {
        return ArchRuleDefinition.noClasses()
                .that().resideInAPackage(PLATFORM_ROOT + "..")
                .and().resideOutsideOfPackage("..internal..")
                .should(new ArchCondition<>("depend on another module's ..internal.. classes") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        origin.getDirectDependenciesFromSelf().forEach(dep -> {
                            String target = dep.getTargetClass().getPackageName();
                            int internal = target.indexOf(".internal");
                            if (target.startsWith(PLATFORM_ROOT) && internal >= 0
                                    && !origin.getPackageName().startsWith(target.substring(0, internal))) {
                                events.add(SimpleConditionEvent.satisfied(origin,
                                        origin.getName() + " reaches into " + target));
                            }
                        });
                    }
                })
                .because("internals are module-private (CLAUDE.md rule 5); use the api/spi types instead");
    }

    /** API root packages (com.acme.platform.&lt;cap&gt; and .annotation) stay dependency-poor. */
    static ArchRule apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore() {
        DescribedPredicate<JavaClass> inApiPackage = new DescribedPredicate<>(
                "reside in an api root package (com.acme.platform.<cap>[.annotation])") {
            @Override
            public boolean test(JavaClass clazz) {
                return clazz.getPackageName().matches("com\\.acme\\.platform\\.[^.]+(\\.annotation)?");
            }
        };
        DescribedPredicate<JavaClass> allowedTarget = new DescribedPredicate<>(
                "jdk, spring core annotations, jspecify, or com.acme.platform.core") {
            @Override
            public boolean test(JavaClass target) {
                String pkg = target.getPackageName();
                return pkg.isEmpty() // primitives and arrays
                        || pkg.startsWith("java") || pkg.startsWith("jdk")
                        || pkg.startsWith("org.springframework.core.annotation")
                        || pkg.startsWith("org.springframework.lang")
                        || pkg.startsWith("org.jspecify")
                        || pkg.startsWith(PLATFORM_ROOT + ".core")
                        || pkg.matches("com\\.acme\\.platform\\.[^.]+(\\.annotation)?"); // own api root
            }
        };
        return ArchRuleDefinition.classes()
                .that(inApiPackage)
                .should().onlyDependOnClassesThat(allowedTarget)
                .because("api packages carry contracts only: jdk + spring core annotations + core (CLAUDE.md rule 5)");
    }

    /** Constructor injection only (CLAUDE.md rule 6). Matches by name so Spring stays optional. */
    static ArchRule noFieldInjection() {
        return ArchRuleDefinition.noFields()
                .should().beAnnotatedWith(AUTOWIRED)
                .because("field injection is banned; use constructor injection (CLAUDE.md rule 6)");
    }

    /** No cycles between capability slices. */
    static ArchRule noCapabilityCycles() {
        return SlicesRuleDefinition.slices()
                .matching(PLATFORM_ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .because("capabilities must form a DAG (CLAUDE.md rule 5)");
    }

    /** Auto-configurations are @AutoConfiguration-annotated and live in ..autoconfigure.. */
    static ArchRule autoConfigurationsAreAnnotatedAndPlaced() {
        return ArchRuleDefinition.classes()
                .that().resideInAPackage(PLATFORM_ROOT + "..")
                .and().haveSimpleNameEndingWith("AutoConfiguration")
                .should().beAnnotatedWith(AUTO_CONFIGURATION)
                .andShould().resideInAPackage("..autoconfigure..")
                .because("the canonical autoconfigure pattern (reference/autoconfigure-pattern.md) requires it");
    }

    /** {@code @ConfigurationProperties} types are immutable records (CLAUDE.md rule 8). */
    static ArchRule configurationPropertiesAreRecords() {
        return ArchRuleDefinition.classes()
                .that().areAnnotatedWith(CONFIGURATION_PROPERTIES)
                .should(new ArchCondition<>("be records") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        if (!clazz.isRecord()) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getName() + " is @ConfigurationProperties but not a record"));
                        }
                    }
                })
                .because("properties are immutable records with defaults in code (CLAUDE.md rule 8)");
    }
}
