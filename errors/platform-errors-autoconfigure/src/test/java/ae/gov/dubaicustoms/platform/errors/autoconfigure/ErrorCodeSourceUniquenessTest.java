package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time uniqueness of error codes across EVERY platform module (phase-01 scope R3.7).
 * {@link ErrorCodeRegistryTest} only sees the classes on this module's test classpath, which misses
 * codes declared in modules this one does not depend on (REST client, messaging, locking, storage,
 * security…). This gate reads the production sources of the whole repository instead, so a duplicate
 * anywhere fails the build. Consumer examples and the archetype are excluded: they model separate
 * services with their own namespaces.
 */
class ErrorCodeSourceUniquenessTest {

    private static final Pattern DECLARATION = Pattern.compile("new ErrorCode\\(\"([^\"]+)\"\\)");
    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");

    private record Declaration(String code, Path file) {
    }

    @Test
    void everyPlatformErrorCodeIsWellFormedAndDeclaredOnce() throws IOException {
        List<Declaration> declarations = scan(repositoryRoot());

        // A near-empty result means the gate stopped looking, not that the platform has no codes.
        assertThat(declarations).extracting(Declaration::code)
                .contains("DC-CORE-0500", "DC-CORE-0400", "DC-CORE-0410", "DC-CORE-0499",
                        "DC-SEC-0401", "DC-SEC-0403", "DC-RCLIENT-0500", "DC-MSG-0001");
        assertThat(declarations).allSatisfy(d -> assertThat(d.code()).as("format in %s", d.file()).matches(FORMAT));

        Map<String, List<Path>> byCode = declarations.stream().collect(
                Collectors.groupingBy(Declaration::code, Collectors.mapping(Declaration::file, Collectors.toList())));
        Map<String, List<Path>> duplicates = byCode.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(duplicates).as("error codes are contracts and must be unique platform-wide").isEmpty();
    }

    private static List<Declaration> scan(Path root) throws IOException {
        List<Declaration> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .filter(ErrorCodeSourceUniquenessTest::isPlatformProductionSource)
                    .forEach(file -> found.addAll(declarationsIn(file)));
        }
        return found;
    }

    private static boolean isPlatformProductionSource(Path file) {
        String path = file.toString().replace('\\', '/');
        return path.contains("/src/main/java/")
                && !path.contains("/target/")
                && !path.contains("/examples/")
                && !path.contains("/tooling/")
                && !path.contains("/scratch/")
                && !path.contains("/tmpviol/");
    }

    private static List<Declaration> declarationsIn(Path file) {
        List<Declaration> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                // Javadoc and comments show example codes; only real declarations count.
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                Matcher matcher = DECLARATION.matcher(line);
                while (matcher.find()) {
                    found.add(new Declaration(matcher.group(1), file));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("build/platform-parent/pom.xml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();
        return dir;
    }
}
