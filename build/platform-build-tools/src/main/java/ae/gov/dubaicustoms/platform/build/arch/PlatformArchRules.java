package ae.gov.dubaicustoms.platform.build.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Map;
import java.util.Set;

// Bytecode-level half of the constitution: rules the POM-level PlatformLayerRule cannot see
// (package boundaries, injection style, cycles). Every module runs these via ArchConstitutionTest.
public final class PlatformArchRules {

    private static final String PLATFORM_ROOT = "ae.gov.dubaicustoms.platform";
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
                .because("internals are module-private (CLAUDE.md rule 5); use the api/spi types instead")
                .allowEmptyShould(true); // fresh/POM-only modules have no classes yet
    }

    /**
     * The ONLY sanctioned third-party exceptions to "api packages carry contracts only", keyed by
     * capability. Each entry is a standard model the capability's contract is defined in terms of:
     * errors — {@code org.springframework.http} because ProblemDetail IS the RFC-9457 model
     * (phase-04 ADR, errors-api README); validation — {@code jakarta.validation} because Bean
     * Validation constraints must be meta-annotated with it; restclient —
     * {@code org.springframework.web.client} because {@code RestClient.Builder} IS the model
     * {@code PlatformRestClientFactory} hands back (phase-06 ADR, decision D19); security —
     * {@code org.springframework.security} because {@code HttpSecurity} IS the model
     * {@code SecurityCustomizer} configures (phase-06 ADR, decision D19). Additions require an ADR.
     */
    private static final Map<String, Set<String>> API_STANDARD_MODEL_PACKAGES = Map.of(
            "errors", Set.of("org.springframework.http"),
            "validation", Set.of("jakarta.validation"),
            "restclient", Set.of("org.springframework.web.client"),
            "security", Set.of("org.springframework.security"));

    /** API root packages (ae.gov.dubaicustoms.platform.&lt;cap&gt; and .annotation) stay dependency-poor. */
    static ArchRule apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore() {
        DescribedPredicate<JavaClass> inApiPackage = new DescribedPredicate<>(
                "reside in an api root package (ae.gov.dubaicustoms.platform.<cap>[.annotation])") {
            @Override
            public boolean test(JavaClass clazz) {
                return clazz.getPackageName().matches("ae\\.gov\\.dubaicustoms\\.platform\\.[^.]+(\\.annotation)?");
            }
        };
        return ArchRuleDefinition.classes()
                .that(inApiPackage)
                .should(new ArchCondition<>(
                        "depend only on jdk, spring core annotations, jspecify, core, own capability, "
                                + "or the capability's whitelisted standard model") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        String capabilityPackage = capabilityPackageOf(origin.getPackageName());
                        Set<String> standardModels = API_STANDARD_MODEL_PACKAGES.getOrDefault(
                                capabilityPackage.substring(PLATFORM_ROOT.length() + 1), Set.of());
                        origin.getDirectDependenciesFromSelf().forEach(dep -> {
                            String pkg = dep.getTargetClass().getPackageName();
                            boolean allowed = isUniversallyAllowedApiTarget(pkg)
                                    // own capability: e.g. constraint annotations referencing
                                    // their validators under .internal
                                    || pkg.startsWith(capabilityPackage)
                                    || standardModels.stream().anyMatch(pkg::startsWith);
                            if (!allowed) {
                                events.add(SimpleConditionEvent.violated(origin, dep.getDescription()));
                            }
                        });
                    }
                })
                .because("api packages carry contracts only: jdk + spring core annotations + core, plus "
                        + "per-capability standard models (CLAUDE.md rule 5)")
                .allowEmptyShould(true);
    }

    /** Targets every api root package may use, regardless of capability. */
    private static boolean isUniversallyAllowedApiTarget(String pkg) {
        return pkg.isEmpty() // primitives and arrays
                || pkg.startsWith("java") || pkg.startsWith("jdk")
                || pkg.startsWith("org.springframework.core.annotation")
                || pkg.startsWith("org.springframework.lang")
                || pkg.startsWith("org.jspecify")
                || pkg.startsWith(PLATFORM_ROOT + ".core")
                || pkg.matches("ae\\.gov\\.dubaicustoms\\.platform\\.[^.]+(\\.annotation)?"); // any api root
    }

    /** ae.gov.dubaicustoms.platform.errors[.annotation] -> ae.gov.dubaicustoms.platform.errors */
    private static String capabilityPackageOf(String apiPackage) {
        String rest = apiPackage.substring(PLATFORM_ROOT.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? apiPackage : PLATFORM_ROOT + "." + rest.substring(0, dot);
    }

    /** Constructor injection only (CLAUDE.md rule 6). Matches by name so Spring stays optional. */
    static ArchRule noFieldInjection() {
        return ArchRuleDefinition.noFields()
                .should().beAnnotatedWith(AUTOWIRED)
                .because("field injection is banned; use constructor injection (CLAUDE.md rule 6)")
                .allowEmptyShould(true);
    }

    /** No cycles between capability slices. */
    static ArchRule noCapabilityCycles() {
        return SlicesRuleDefinition.slices()
                .matching(PLATFORM_ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .because("capabilities must form a DAG (CLAUDE.md rule 5)")
                .allowEmptyShould(true);
    }

    /** Auto-configurations are @AutoConfiguration-annotated and live in ..autoconfigure.. */
    static ArchRule autoConfigurationsAreAnnotatedAndPlaced() {
        return ArchRuleDefinition.classes()
                .that().resideInAPackage(PLATFORM_ROOT + "..")
                .and().haveSimpleNameEndingWith("AutoConfiguration")
                .should().beAnnotatedWith(AUTO_CONFIGURATION)
                .andShould().resideInAPackage("..autoconfigure..")
                .because("the canonical autoconfigure pattern (reference/autoconfigure-pattern.md) requires it")
                .allowEmptyShould(true);
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
                .because("properties are immutable records with defaults in code (CLAUDE.md rule 8)")
                .allowEmptyShould(true);
    }
}
