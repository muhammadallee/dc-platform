package ae.gov.dubaicustoms.platform.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared filesystem + naming helpers for the phase-14 docs generators and gates. The generators run
 * as JUnit tests during {@code verify}; they read sibling modules' build outputs from the reactor
 * tree rather than depending on those modules (spec: "a test-scope main() in platform-docs, run at
 * build"), so this class resolves the repo root by walking up from the module directory.
 */
final class PlatformDocs {

    private PlatformDocs() {
    }

    /** Walks up from the working directory to the reactor root (the dir holding platform-bom). */
    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("build/platform-bom/pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate the reactor root (no build/platform-bom/pom.xml above "
                + Path.of("").toAbsolutePath() + ")");
    }

    /** The authored + generated markdown tree at the repo root. */
    static Path docsRoot() {
        return repoRoot().resolve("docs");
    }

    /**
     * Every {@code platform-starter-*} module mapped to the capability page that must document it.
     * Multi-provider capabilities (cache, messaging, storage, locking) and the security/authz split
     * share one page per capability; {@code platform-starter-test} maps to the top-level testing page.
     */
    static final Map<String, String> STARTER_TO_PAGE = buildStarterPageMap();

    private static Map<String, String> buildStarterPageMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("platform-starter-core", "modules/core.md");
        m.put("platform-starter-errors", "modules/errors.md");
        m.put("platform-starter-logging", "modules/logging.md");
        m.put("platform-starter-validation", "modules/validation.md");
        m.put("platform-starter-observability", "modules/observability.md");
        m.put("platform-starter-openapi", "modules/openapi.md");
        m.put("platform-starter-restclient", "modules/restclient.md");
        m.put("platform-starter-security", "modules/security.md");
        m.put("platform-starter-security-authz", "modules/authz.md");
        m.put("platform-starter-messaging-inmemory", "modules/messaging.md");
        m.put("platform-starter-messaging-kafka", "modules/messaging.md");
        m.put("platform-starter-messaging-rabbit", "modules/messaging.md");
        m.put("platform-starter-events", "modules/events.md");
        m.put("platform-starter-data-jpa", "modules/data.md");
        m.put("platform-starter-cache-caffeine", "modules/cache.md");
        m.put("platform-starter-cache-redis", "modules/cache.md");
        m.put("platform-starter-redis", "modules/redis.md");
        m.put("platform-starter-resilience", "modules/resilience.md");
        m.put("platform-starter-locking-jdbc", "modules/locking.md");
        m.put("platform-starter-locking-redis", "modules/locking.md");
        m.put("platform-starter-scheduling", "modules/scheduling.md");
        m.put("platform-starter-idempotency", "modules/idempotency.md");
        m.put("platform-starter-storage-fs", "modules/storage.md");
        m.put("platform-starter-storage-s3", "modules/storage.md");
        m.put("platform-starter-audit", "modules/audit.md");
        m.put("platform-starter-ratelimit", "modules/ratelimit.md");
        m.put("platform-starter-files", "modules/files.md");
        m.put("platform-starter-flags", "modules/flags.md");
        m.put("platform-starter-test", "testing.md");
        return m;
    }

    /** Every {@code platform-starter-*} artifactId declared in the root reactor POM. */
    static List<String> starterModules() {
        String rootPom;
        try {
            rootPom = Files.readString(repoRoot().resolve("pom.xml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> starters = new ArrayList<>();
        Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(rootPom);
        while (m.find()) {
            String artifactId = m.group(1).substring(m.group(1).lastIndexOf('/') + 1);
            if (artifactId.startsWith("platform-starter-")) {
                starters.add(artifactId);
            }
        }
        return starters;
    }

    /** The capability page a starter must be documented on, or {@code null} if unmapped. */
    static Path pageForStarter(String starterArtifactId) {
        String page = STARTER_TO_PAGE.get(starterArtifactId);
        return page == null ? null : docsRoot().resolve(page);
    }

    /** The capability short name from {@code dc.platform.<cap>...} or {@code DC-<CAP>-NNNN}. */
    static String capabilityOfKey(String propertyKey) {
        String rest = propertyKey.substring("dc.platform.".length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    static void writeString(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
