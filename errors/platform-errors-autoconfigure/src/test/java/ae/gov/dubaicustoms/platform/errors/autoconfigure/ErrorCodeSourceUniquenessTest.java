package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Build-time uniqueness of error codes across EVERY platform module (phase-01 scope R3.7, decision D92; carried by
 * Spring-native Phase 2, row C24). A platform error code is a {@code String} literal of the form
 * {@code DC-<CAP>-<NNNN>}, normally a {@code static final String} constant set as the {@code code} property of a
 * {@code ProblemDetail} (D138). This gate parses the production sources of the whole repository with the JDK's own
 * Java parser, so formatting, comments, escapes and line breaks cannot hide a declaration, and fails closed: a literal
 * that starts like a code but is not one ({@code "DC-TST-"} concatenated with a number, {@code "DC-TST-12"}) is
 * reported, not skipped. Every code value may be declared once. A minimum count stops an empty scan from passing.
 * Consumer examples and the archetype are excluded (separate services).
 */
class ErrorCodeSourceUniquenessTest {

    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");
    private static final String PREFIX = "DC-";

    /** The platform's codes at Phase 2: errors (7), messaging (2), the parked REST client (1). */
    private static final int MINIMUM_CODES = 10;

    record Declaration(String code, String file) {
    }

    record Scan(List<Declaration> declarations, List<String> unsupported) {
    }

    @Test
    void everyPlatformErrorCodeIsWellFormedAndDeclaredOnce() throws IOException {
        Scan scan = scan(platformSources(repositoryRoot()));

        assertThat(scan.unsupported()).as("an error code must be one whole DC-<CAP>-<NNNN> literal").isEmpty();
        // A near-empty result means the gate stopped looking, not that the platform has no codes.
        assertThat(scan.declarations()).hasSizeGreaterThanOrEqualTo(MINIMUM_CODES);
        assertThat(scan.declarations()).extracting(Declaration::code)
                .contains("DC-CORE-0500", "DC-CORE-0400", "DC-CORE-0410", "DC-CORE-0499",
                        "DC-DATA-0409", "DC-DATA-0410", "DC-DATA-0503", "DC-MSG-0001", "DC-RCLIENT-0500");
        assertThat(duplicates(scan)).as("error codes are contracts and must be unique platform-wide").isEmpty();
    }

    @Test
    void theScannerSeesEveryFormattingAndRejectsPartialCodes() {
        Scan scan = scan(List.of(
                source("A", """
                        class A {
                            static final String SPLIT_OVER_LINES =
                                    "DC-TST-0001";
                            // "DC-TST-9999" in a comment is not a declaration
                            String text = "the code DC-TST-8888 inside a sentence is not one either";
                        }
                        """),
                source("B", """
                        class B {
                            static final String ESCAPED = "DC-TST-\\u0030001";
                            static final String FROM_CONSTANT = PREFIX + "0002";
                            static final String PREFIX = "DC-TST-";
                        }
                        """),
                source("C", """
                        class C {
                            static final String SHORT = "DC-TST-12";
                        }
                        """)));

        assertThat(scan.declarations()).extracting(Declaration::code)
                .as("multi-line, and a unicode escape resolving to the same runtime value")
                .containsExactlyInAnyOrder("DC-TST-0001", "DC-TST-0001");
        assertThat(duplicates(scan)).containsOnlyKeys("DC-TST-0001");
        assertThat(scan.unsupported()).hasSize(2)
                .anySatisfy(entry -> assertThat(entry).contains("B").contains("DC-TST-"))
                .anySatisfy(entry -> assertThat(entry).contains("C").contains("DC-TST-12"));
    }

    private static Map<String, List<String>> duplicates(Scan scan) {
        return scan.declarations().stream()
                .collect(Collectors.groupingBy(Declaration::code, Collectors.mapping(Declaration::file, Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Scan scan(List<JavaFileObject> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
        }, List.of("-proc:none"), null, sources);
        List<Declaration> declarations = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        try {
            for (CompilationUnitTree unit : task.parse()) {
                String file = unit.getSourceFile().getName();
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitLiteral(LiteralTree node, Void unused) {
                        if (node.getValue() instanceof String text && text.startsWith(PREFIX)) {
                            if (FORMAT.matcher(text).matches()) {
                                declarations.add(new Declaration(text, file));
                            } else {
                                unsupported.add(file + ": \"" + text + "\"");
                            }
                        }
                        return super.visitLiteral(node, unused);
                    }
                }.scan(unit, null);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new Scan(declarations, unsupported);
    }

    private static JavaFileObject source(String name, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static List<JavaFileObject> platformSources(Path root) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (Stream<Path> files = Files.walk(root);
                var fileManager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            List<Path> paths = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(ErrorCodeSourceUniquenessTest::isPlatformProductionSource)
                    .toList();
            List<JavaFileObject> sources = new ArrayList<>();
            fileManager.getJavaFileObjectsFromPaths(paths).forEach(sources::add);
            return sources;
        }
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

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("build/platform-parent/pom.xml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above %s", Path.of("").toAbsolutePath()).isNotNull();
        return dir;
    }
}
