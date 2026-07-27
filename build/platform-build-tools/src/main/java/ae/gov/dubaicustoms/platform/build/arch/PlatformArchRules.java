package ae.gov.dubaicustoms.platform.build.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaModifier;
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
    private static final String API_ANNOTATION = "org.apiguardian.api.API";
    private static final String DEPRECATED_ANNOTATION = "java.lang.Deprecated";

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
            publicApiSpiTypesCarryApiStatus(),
            deprecatedApiStatusAndDeprecatedAnnotationCoOccur(),
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
                || pkg.startsWith("org.apiguardian") // @API stability marker (phase-16 A.1)
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

    /**
     * Every public API/SPI contract type carries an apiguardian {@code @API(status, since)} marker
     * (phase-16 A.1): a Layer-0 discovery signal read straight from the jar. Scoped to the published
     * contract surface — api root packages ({@code platform.<cap>[.…]}), any {@code ..spi..}
     * package, the core api sub-packages, and the test-api packages — so provider-impl, autoconfigure
     * and internal types (which are not consumer contracts) are exempt. {@code @PlatformApi} is kept
     * alongside for enforcer targeting; the two are complementary (decision D79).
     */
    static ArchRule publicApiSpiTypesCarryApiStatus() {
        return ArchRuleDefinition.classes()
                .that(arePublishedContractTypes())
                .should(new ArchCondition<>("be annotated with @org.apiguardian.api.API") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        if (!clazz.isAnnotatedWith(API_ANNOTATION)) {
                            events.add(SimpleConditionEvent.violated(clazz, clazz.getName()
                                    + " is a public API/SPI type but carries no @API(status=…, since=…)"));
                        }
                    }
                })
                .because("published API/SPI types must declare an apiguardian @API status so consumers "
                        + "and agents can read stability straight from the jar (phase-16 A.1)")
                .allowEmptyShould(true);
    }

    /**
     * {@code @API(status = DEPRECATED)} and {@code java.lang.@Deprecated} must co-occur on any
     * {@code @API}-annotated type (phase-16 A.1, user guard): apiguardian DEPRECATED without the
     * language {@code @Deprecated} leaves compilers and IDEs silent, and a language {@code @Deprecated}
     * whose {@code @API} status is not DEPRECATED contradicts the machine-readable stability signal.
     */
    static ArchRule deprecatedApiStatusAndDeprecatedAnnotationCoOccur() {
        return ArchRuleDefinition.classes()
                .that().areAnnotatedWith(API_ANNOTATION)
                .should(new ArchCondition<>(
                        "carry @Deprecated exactly when @API(status = DEPRECATED)") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        boolean apiDeprecated = "DEPRECATED".equals(apiStatus(clazz));
                        boolean langDeprecated = clazz.isAnnotatedWith(DEPRECATED_ANNOTATION);
                        if (apiDeprecated && !langDeprecated) {
                            events.add(SimpleConditionEvent.violated(clazz, clazz.getName()
                                    + " is @API(status = DEPRECATED) but not @java.lang.Deprecated"));
                        }
                        if (langDeprecated && !apiDeprecated) {
                            events.add(SimpleConditionEvent.violated(clazz, clazz.getName()
                                    + " is @Deprecated but its @API status is '" + apiStatus(clazz)
                                    + "' (must be DEPRECATED)"));
                        }
                    }
                })
                .because("apiguardian DEPRECATED and java.lang.@Deprecated must co-occur so deprecation "
                        + "is a machine-readable guarantee, not just documentation (phase-16 A.1)")
                .allowEmptyShould(true);
    }

    /** The published contract surface the {@code @API} presence rule governs (see rule javadoc). */
    private static DescribedPredicate<JavaClass> arePublishedContractTypes() {
        return new DescribedPredicate<>("public API/SPI contract types") {
            @Override
            public boolean test(JavaClass clazz) {
                return clazz.getModifiers().contains(JavaModifier.PUBLIC)
                        && clazz.isTopLevelClass()
                        && !clazz.getSimpleName().isEmpty()
                        && !"package-info".equals(clazz.getSimpleName())
                        && isContractPackage(clazz.getPackageName());
            }
        };
    }

    /**
     * True for the published contract packages: an api root ({@code platform.<cap>}), any
     * {@code .spi} package, the two core api sub-packages, or a test-api package — never an
     * {@code ..internal..} package.
     */
    private static boolean isContractPackage(String pkg) {
        if (pkg.contains(".internal")) {
            return false;
        }
        return pkg.matches("ae\\.gov\\.dubaicustoms\\.platform\\.[^.]+")            // api root
                || pkg.matches("ae\\.gov\\.dubaicustoms\\.platform\\..*\\.spi(\\..*)?") // any spi
                || pkg.equals(PLATFORM_ROOT + ".core.context")
                || pkg.equals(PLATFORM_ROOT + ".core.report")
                || pkg.matches("ae\\.gov\\.dubaicustoms\\.platform\\.test\\.[^.]+"); // test-api
    }

    /** The {@code name()} of a type's {@code @API} {@code status} enum, or {@code ""} if unset. */
    private static String apiStatus(JavaClass clazz) {
        return clazz.getAnnotations().stream()
                .filter(a -> a.getRawType().getName().equals(API_ANNOTATION))
                .findFirst()
                .flatMap(a -> a.get("status"))
                .filter(JavaEnumConstant.class::isInstance)
                .map(v -> ((JavaEnumConstant) v).name())
                .orElse("");
    }
}
