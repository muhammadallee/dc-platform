package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
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
 * Build-time uniqueness of error codes across EVERY platform module (phase-01 scope R3.7, decision D92).
 * {@link ErrorCodeRegistryTest} only sees the classes on this module's test classpath, which misses codes
 * declared in modules this one does not depend on. This gate parses the production sources of the whole
 * repository with the JDK's own Java parser, so formatting, comments, qualified names and escapes cannot
 * hide a declaration, and fails closed: an {@code ErrorCode} constructed from anything but a string
 * literal is reported, not skipped. Consumer examples and the archetype are excluded (separate services).
 */
class ErrorCodeSourceUniquenessTest {

    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");

    record Declaration(String code, String file) {
    }

    record Scan(List<Declaration> declarations, List<String> unsupported) {
    }

    @Test
    void everyPlatformErrorCodeIsWellFormedAndDeclaredOnce() throws IOException {
        Scan scan = scan(platformSources(repositoryRoot()));

        assertThat(scan.unsupported()).as("ErrorCode must be constructed from a string literal").isEmpty();
        // A near-empty result means the gate stopped looking, not that the platform has no codes.
        assertThat(scan.declarations()).extracting(Declaration::code)
                .contains("DC-CORE-0500", "DC-CORE-0400", "DC-CORE-0410", "DC-CORE-0499",
                        "DC-SEC-0401", "DC-SEC-0403", "DC-RCLIENT-0500", "DC-MSG-0001");
        assertThat(scan.declarations()).allSatisfy(d -> assertThat(d.code()).as("format in %s", d.file()).matches(FORMAT));
        assertThat(duplicates(scan)).as("error codes are contracts and must be unique platform-wide").isEmpty();
    }

    @Test
    void theScannerSeesEveryFormattingAndRejectsNonLiterals() {
        Scan scan = scan(List.of(
                source("A", """
                        class A {
                            static final ae.gov.dubaicustoms.platform.core.ErrorCode QUALIFIED =
                                    new ae.gov.dubaicustoms.platform.core.ErrorCode(
                                            "DC-TST-0001");
                            // new ErrorCode("DC-TST-9999") in a comment is not a declaration
                            String text = "new ErrorCode(\\"DC-TST-8888\\") inside a string is not one either";
                        }
                        """),
                source("B", """
                        class B {
                            static final ErrorCode ESCAPED = new ErrorCode("DC-TST-\\u0030001");
                            static final ErrorCode FROM_CONSTANT = new ErrorCode(PREFIX + "0002");
                            static final String PREFIX = "DC-TST-";
                        }
                        """)));

        assertThat(scan.declarations()).extracting(Declaration::code)
                .as("multi-line + qualified, and a unicode escape resolving to the same runtime value")
                .containsExactlyInAnyOrder("DC-TST-0001", "DC-TST-0001");
        assertThat(duplicates(scan)).containsOnlyKeys("DC-TST-0001");
        assertThat(scan.unsupported()).singleElement().asString().contains("B");
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
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        if (isErrorCode(node.getIdentifier())) {
                            ExpressionTree argument = node.getArguments().size() == 1 ? node.getArguments().get(0) : null;
                            if (argument instanceof LiteralTree literal && literal.getValue() instanceof String code) {
                                declarations.add(new Declaration(code, file));
                            } else {
                                unsupported.add(file + ": " + node);
                            }
                        }
                        return super.visitNewClass(node, unused);
                    }
                }.scan(unit, null);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new Scan(declarations, unsupported);
    }

    private static boolean isErrorCode(ExpressionTree type) {
        return (type instanceof IdentifierTree identifier && identifier.getName().contentEquals("ErrorCode"))
                || (type instanceof MemberSelectTree select && select.getIdentifier().contentEquals("ErrorCode"));
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
