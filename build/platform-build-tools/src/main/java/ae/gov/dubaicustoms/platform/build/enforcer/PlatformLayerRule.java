package ae.gov.dubaicustoms.platform.build.enforcer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;

// Executable form of the dependency constitution (CLAUDE.md rule 5). Without it, layer violations
// only surface in review; with it, they fail the build with a message naming the rule and the fix.
@Named("platformLayerRule")
public final class PlatformLayerRule extends AbstractEnforcerRule {

    /** Module category, inferred from artifactId suffix/prefix (and basedir for build/). */
    enum Category {
        API, SPI, IMPLEMENTATION, AUTOCONFIGURE, STARTER, TEST_SUPPORT, BUILD, EXAMPLES;

        static Category of(MavenProject project) {
            if (project.getBasedir() != null && project.getBasedir().getParentFile() != null
                    && "build".equals(project.getBasedir().getParentFile().getName())) {
                return BUILD;
            }
            String a = project.getArtifactId();
            if (a.startsWith("example-")) {
                return EXAMPLES;
            }
            return ofArtifactId(a);
        }

        static Category ofArtifactId(String a) {
            if (a.startsWith("platform-build-")) {
                return BUILD;
            }
            if (a.startsWith("platform-starter-")) {
                return STARTER;
            }
            // A "*-test-api" module (e.g. platform-test-api) is Test Support, not API: it composes
            // slices and fixtures across capabilities, so the API matrix (core-api only) can't apply.
            // Checked before "-api" so the suffix doesn't win.
            if (a.endsWith("-test-api")) {
                return TEST_SUPPORT;
            }
            if (a.endsWith("-api")) {
                return API;
            }
            if (a.endsWith("-spi")) {
                return SPI;
            }
            if (a.endsWith("-autoconfigure")) {
                return AUTOCONFIGURE;
            }
            if (a.endsWith("-test") || a.startsWith("tck-") || a.contains("-tck")) {
                return TEST_SUPPORT;
            }
            return IMPLEMENTATION;
        }
    }

    private static final String PLATFORM_GROUP_ID = "ae.gov.dubaicustoms.platform";
    private static final String CORE_API = "platform-core-api";

    // Fan-out ceilings (platform deps only) from phase-02 spec.
    private static final int MAX_API = 1;
    private static final int MAX_SPI = 2;
    private static final int MAX_IMPL = 3;
    private static final int MAX_AUTOCONFIGURE = 6;
    private static final int MAX_STARTER = 4;

    private final MavenProject project;

    @Inject
    public PlatformLayerRule(MavenProject project) {
        this.project = project;
    }

    @Override
    public void execute() throws EnforcerRuleException {
        List<String> violations = new ArrayList<>();
        Category category = Category.of(project);

        if (category != Category.BUILD) {
            checkNoLiteralVersions(violations);
        }
        if (category != Category.BUILD && category != Category.EXAMPLES) {
            List<Dependency> platformDeps = platformDependencies();
            checkMatrix(category, platformDeps, violations);
            checkFanOut(category, platformDeps.size(), violations);
        }

        if (!violations.isEmpty()) {
            throw new EnforcerRuleException(
                    "PlatformLayerRule: " + project.getArtifactId() + " violates the dependency constitution "
                            + "(CLAUDE.md rule 5):\n  - " + String.join("\n  - ", violations));
        }
    }

    /** Direct platform dependencies that can leak into consumers (test scope is exempt). */
    private List<Dependency> platformDependencies() {
        List<Dependency> result = new ArrayList<>();
        for (Dependency d : project.getDependencies()) {
            if (PLATFORM_GROUP_ID.equals(d.getGroupId()) && !"test".equals(d.getScope())) {
                result.add(d);
            }
        }
        return result;
    }

    private void checkMatrix(Category category, List<Dependency> deps, List<String> violations) {
        String cap = capabilityOf(project.getArtifactId());
        for (Dependency d : deps) {
            String depId = d.getArtifactId();
            Category depCat = Category.ofArtifactId(depId);
            String depCap = capabilityOf(depId);
            boolean sameCap = depCap.equals(cap);

            String violation = switch (category) {
                case API -> CORE_API.equals(depId) ? null
                        : "api -> " + depId + " is forbidden: 'api -> core-api only'. Move shared types into "
                                + "platform-core-api or drop the dependency.";
                case SPI -> CORE_API.equals(depId) || (sameCap && depCat == Category.API) ? null
                        : "spi -> " + depId + " is forbidden: 'spi -> same-capability api (+ core-api)'. Depend on "
                                + "platform-" + cap + "-api instead, or move the contract there.";
                case IMPLEMENTATION -> implViolation(depId, depCat, sameCap, cap);
                case AUTOCONFIGURE -> autoconfigureViolation(depId, depCat, sameCap, cap);
                case STARTER -> starterViolation(depId, depCat, sameCap, cap);
                case TEST_SUPPORT -> depCat == Category.STARTER
                        ? "test-support -> " + depId + " is forbidden: test kits depend on api/spi/impl, never on "
                                + "starters. Depend on the autoconfigure or impl module directly."
                        : null;
                case BUILD, EXAMPLES -> null; // exempt; not reached
            };
            if (violation != null) {
                violations.add(violation);
            }
        }
    }

    private static String implViolation(String depId, Category depCat, boolean sameCap, String cap) {
        if (depCat == Category.IMPLEMENTATION && !CORE_API.equals(depId)) {
            return "impl -> impl (" + depId + ") is forbidden: implementations must not know each other. Extract the "
                    + "shared code into platform-" + cap + "-spi/-api or compose at the autoconfigure level.";
        }
        if (CORE_API.equals(depId) || (sameCap && (depCat == Category.API || depCat == Category.SPI))) {
            return null;
        }
        return "impl -> " + depId + " is forbidden: 'impl -> same-capability spi/api (+ core-api + its third-party "
                + "lib)'. Reach other capabilities through their api from the autoconfigure layer instead.";
    }

    private static String autoconfigureViolation(String depId, Category depCat, boolean sameCap, String cap) {
        if (depCat == Category.STARTER) {
            return "autoconfigure -> starter (" + depId + ") is forbidden: starters aggregate autoconfigure modules, "
                    + "never the reverse. Depend on the impl or api module the starter would bring.";
        }
        if (CORE_API.equals(depId) || depCat == Category.API
                || (sameCap && (depCat == Category.SPI || depCat == Category.IMPLEMENTATION))) {
            return null; // other capabilities only via their api (guard usage with @ConditionalOnClass)
        }
        return "autoconfigure -> " + depId + " is forbidden: other capabilities may be reached only through their "
                + "-api module (guarded by @ConditionalOnClass). Depend on platform-" + capabilityOf(depId)
                + "-api instead.";
    }

    private static String starterViolation(String depId, Category depCat, boolean sameCap, String cap) {
        if ("test".equals(cap)) {
            // platform-starter-test aggregates the platform's cross-capability test kits (test-api,
            // messaging-test, ...), like spring-boot-starter-test — the one starter allowed to depend
            // on Test Support modules. It still may not depend on another starter.
            return depCat == Category.STARTER
                    ? "starter -> starter is forbidden: move the shared dependency into the autoconfigure "
                            + "module or the consumer's POM (offending dependency: " + depId + ")."
                    : null;
        }
        if (depCat == Category.STARTER) {
            // Message wording fixed by phase-02 spec.
            return "starter -> starter is forbidden: move the shared dependency into the autoconfigure module or "
                    + "the consumer's POM (offending dependency: " + depId + ").";
        }
        if (sameCap && (depCat == Category.AUTOCONFIGURE || depCat == Category.IMPLEMENTATION)) {
            return null;
        }
        return "starter -> " + depId + " is forbidden: 'starter -> same-capability autoconfigure + named impl(s)'. "
                + "Anything else belongs in the autoconfigure module's optional dependencies.";
    }

    private void checkFanOut(Category category, int platformDepCount, List<String> violations) {
        Integer max = switch (category) {
            case API -> MAX_API;
            case SPI -> MAX_SPI;
            case IMPLEMENTATION -> MAX_IMPL;
            case AUTOCONFIGURE -> MAX_AUTOCONFIGURE;
            case STARTER -> MAX_STARTER;
            default -> null;
        };
        if (max != null && platformDepCount > max) {
            violations.add("fan-out ceiling exceeded: " + platformDepCount + " platform dependencies but "
                    + category.name().toLowerCase(java.util.Locale.ROOT) + " allows at most " + max
                    + ". Split the module or push dependencies down to the consumer.");
        }
    }

    /**
     * No literal version tags outside build/ (phase-01 rule; checked here per phase-02 spec).
     * Property expressions like ${revision} stay allowed: they resolve to centrally managed values.
     */
    private void checkNoLiteralVersions(List<String> violations) {
        Model original = project.getOriginalModel();
        if (original == null) {
            return;
        }
        for (Dependency d : original.getDependencies()) {
            if (isLiteral(d.getVersion())) {
                violations.add("literal <version> on dependency " + d.getGroupId() + ":" + d.getArtifactId()
                        + " violates 'no <version> outside build/': pin third-party versions in "
                        + "build/platform-dependencies, platform artifacts in platform-parent's dependencyManagement.");
            }
        }
        if (original.getBuild() != null) {
            for (org.apache.maven.model.Plugin p : original.getBuild().getPlugins()) {
                if (isLiteral(p.getVersion())) {
                    violations.add("literal <version> on plugin " + p.getKey() + " violates 'no <version> outside "
                            + "build/': manage plugin versions in platform-parent's pluginManagement.");
                }
            }
        }
    }

    private static boolean isLiteral(String version) {
        return version != null && !version.contains("${");
    }

    static String capabilityOf(String artifactId) {
        String rest = artifactId;
        if (rest.startsWith("platform-starter-")) {
            rest = rest.substring("platform-starter-".length());
        } else if (rest.startsWith("platform-")) {
            rest = rest.substring("platform-".length());
        }
        int dash = rest.indexOf('-');
        return dash < 0 ? rest : rest.substring(0, dash);
    }

    @Override
    public String getCacheId() {
        return null; // never cache: verdict depends on each module's own POM
    }
}
