package ae.gov.dubaicustoms.platform.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Holds the platform's authored usage snippets honest against the live API: every fenced Java block
 * in {@code docs/modules/*.md} or anywhere under {@code docs/book/} tagged {@code snippet:<id>} is
 * compiled in-memory against the platform types on the test classpath. If an API a snippet
 * demonstrates is renamed or its signature changes, the snippet stops compiling and this gate fails —
 * so neither the examples the MCP server / index hand to agents nor the book's teaching examples can
 * silently rot.
 *
 * <p>Only {@code snippet:}-tagged blocks are compiled. Untagged Java blocks in the pages are
 * intentionally illustrative (partial fragments, {@code ...} elisions, undeclared domain types) and
 * are not compilation units; tagging a block opts it into this gate (and, on a capability page, via
 * {@link PlatformIndexGenerator}, makes it the capability's published snippet — book snippets are
 * compiled but never published, since the index only reads capability pages). Authored snippets need
 * no {@code import} lines — a preamble of the platform packages found on the classpath (plus the
 * common Spring/Jakarta packages a usage example draws on) is prepended before compilation, so the
 * docs stay readable.
 */
class UsageSnippetCompileTest {

    private static final Pattern FENCE = Pattern.compile("```([^\\n`]*)\\n(.*?)```", Pattern.DOTALL);

    /** Third-party packages a usage snippet may reference by simple name; each is included only if present. */
    private static final List<String> CANDIDATE_THIRD_PARTY_IMPORTS = List.of(
            "org.springframework.stereotype",
            "org.springframework.context.annotation",
            "org.springframework.beans.factory.annotation",
            "org.springframework.web.bind.annotation",
            "org.springframework.transaction.annotation",
            "org.springframework.cache.annotation",
            "org.springframework.validation.annotation",
            "org.springframework.core.annotation",
            "org.springframework.data.redis.core",
            "org.springframework.web.client",
            "org.springframework.http",
            "jakarta.validation",
            "jakarta.validation.constraints",
            "jakarta.persistence",
            "org.springframework.data.annotation",
            "org.springframework.data.domain",
            "org.slf4j",
            "io.micrometer.core.instrument",
            "org.springdoc.core.customizers",
            "io.github.resilience4j.retry.annotation",
            "io.github.resilience4j.circuitbreaker.annotation",
            "io.github.resilience4j.timelimiter.annotation",
            "org.springframework.scheduling.annotation",
            "java.time",
            "java.util",
            "java.util.concurrent",
            "java.util.stream",
            "java.io");

    /**
     * Simple names offered by more than one on-demand import above, which javac then rejects as
     * ambiguous. A single-type import wins over any on-demand import, so listing the intended type here
     * resolves the clash for every snippet without making authors write a fully-qualified annotation.
     * Only genuine collisions belong here; each is included only if the type is on the classpath.
     */
    private static final List<String> DISAMBIGUATING_IMPORTS = List.of(
            // jakarta.validation.Configuration vs org.springframework.context.annotation.Configuration
            "org.springframework.context.annotation.Configuration",
            // io.micrometer.core.instrument.Clock vs java.time.Clock — docs mean the injectable one
            "java.time.Clock",
            // org.springframework.data.annotation.{Id,Version} vs jakarta.persistence.{Id,Version} —
            // entity snippets mean the JPA ones; Spring Data's are for non-JPA stores.
            "jakarta.persistence.Id",
            "jakarta.persistence.Version",
            // jakarta.persistence.Cacheable (a boolean JPA hint) vs Spring's caching annotation —
            // docs mean Spring's. Without this the JPA one silently wins and @Cacheable("x") fails.
            "org.springframework.cache.annotation.Cacheable");

    private record Snippet(String page, String id, String code) {}

    @TestFactory
    Stream<DynamicTest> taggedJavaUsageSnippetsCompile() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("tests must run on a JDK (getSystemJavaCompiler != null), not a JRE")
                .isNotNull();

        List<Snippet> snippets = collectTaggedJavaSnippets();
        assertThat(snippets)
                .as("expected the seeded snippet:<id> usage examples to be present and discoverable")
                .isNotEmpty();

        List<String> classpathEntries = classpathEntries();
        String preamble = importPreamble(classpathEntries);
        String classpath = String.join(java.io.File.pathSeparator, classpathEntries);

        return snippets.stream().map(s -> DynamicTest.dynamicTest(
                s.page() + " :: snippet:" + s.id(),
                () -> compile(compiler, classpath, preamble, s)));
    }

    private static void compile(JavaCompiler compiler, String classpath, String preamble, Snippet s)
            throws IOException {
        Path outDir = Files.createTempDirectory("snippet-compile-");
        try {
            String source = "package snippets;\n" + preamble + "\n" + s.code() + "\n";
            String className = "Snippet_" + s.id().replaceAll("[^A-Za-z0-9]", "_");

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
                fm.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
                List<String> options = List.of("-classpath", classpath, "-proc:none");
                JavaFileObject file = new StringSource(className, source);
                boolean ok = compiler.getTask(null, fm, diagnostics, options, null, List.of(file)).call();

                assertThat(ok)
                        .as("snippet:%s in %s does not compile against the current platform API%n%s%n"
                                        + "--- snippet source (with generated import preamble) ---%n%s",
                                s.id(), s.page(), render(diagnostics), source)
                        .isTrue();
            }
        } finally {
            deleteRecursively(outDir);
        }
    }

    // --- snippet discovery --------------------------------------------------------------------

    /** The doc trees whose tagged snippets are gated: the capability pages and the whole book. */
    private static final List<String> SNIPPET_ROOTS = List.of("modules", "book");

    private static List<Snippet> collectTaggedJavaSnippets() throws IOException {
        List<Snippet> out = new ArrayList<>();
        Path docs = PlatformDocs.docsRoot();
        for (String root : SNIPPET_ROOTS) {
            Path dir = docs.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue; // a tree that does not exist yet contributes no snippets
            }
            try (Stream<Path> pages = Files.walk(dir)) {
                pages.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(page -> collectFrom(docs, page, out));
            }
        }
        return out;
    }

    private static void collectFrom(Path docs, Path page, List<Snippet> out) {
        String content;
        try {
            content = Files.readString(page);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The docs-relative path, not the bare filename: the book has repeating leaf names.
        String label = docs.relativize(page).toString().replace('\\', '/');
        Matcher m = FENCE.matcher(content);
        while (m.find()) {
            String info = m.group(1).trim();
            int tag = info.indexOf("snippet:");
            if (info.startsWith("java") && tag >= 0) {
                String id = info.substring(tag + "snippet:".length()).trim();
                out.add(new Snippet(label, id, m.group(2).strip()));
            }
        }
    }

    // --- import preamble ----------------------------------------------------------------------

    private static String importPreamble(List<String> classpathEntries) {
        Set<String> packages = new LinkedHashSet<>(platformPackagesOnClasspath(classpathEntries));
        for (String candidate : CANDIDATE_THIRD_PARTY_IMPORTS) {
            if (packageResolvable(candidate)) {
                packages.add(candidate);
            }
        }
        StringBuilder sb = new StringBuilder();
        packages.forEach(p -> sb.append("import ").append(p).append(".*;\n"));
        for (String type : DISAMBIGUATING_IMPORTS) {
            if (typeResolvable(type)) {
                sb.append("import ").append(type).append(";\n");
            }
        }
        return sb.toString();
    }

    /**
     * The actual test classpath as filesystem paths. {@code java.class.path} is unreliable under
     * {@code forkCount=0} (it is Maven's launcher, not the test dependencies), so URLs are also pulled
     * from the Surefire isolated {@link java.net.URLClassLoader} chain; both sources are merged so the
     * gate resolves the same way whether tests run forked or in-process.
     */
    private static List<String> classpathEntries() {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof java.net.URLClassLoader ucl) {
                for (URL url : ucl.getURLs()) {
                    try {
                        entries.add(Path.of(url.toURI()).toString());
                    } catch (Exception ignored) {
                        // a non-file URL (unlikely on a Maven test classpath) is not compilable input
                    }
                }
            }
        }
        return new ArrayList<>(entries);
    }

    /**
     * Every {@code ae.gov.dubaicustoms.platform.*} package that carries at least one public type on the
     * test classpath, excluding {@code .internal} (implementation detail, never referenced by a snippet).
     */
    private static Set<String> platformPackagesOnClasspath(List<String> classpathEntries) {
        Set<String> packages = new LinkedHashSet<>();
        for (String entry : classpathEntries) {
            if (!entry.endsWith(".jar")) {
                continue; // platform artifacts arrive as jars from the local repo on the surefire classpath
            }
            try (ZipFile jar = new ZipFile(entry)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (!name.startsWith("ae/gov/dubaicustoms/platform/")
                            || !name.endsWith(".class")
                            || name.contains("$")
                            || name.endsWith("package-info.class")
                            || name.endsWith("module-info.class")) {
                        continue;
                    }
                    String pkg = name.substring(0, name.lastIndexOf('/')).replace('/', '.');
                    if (!pkg.contains(".internal")) {
                        packages.add(pkg);
                    }
                }
            } catch (IOException e) {
                // a non-readable classpath jar is not this test's concern; skip it
            }
        }
        return packages;
    }

    /** True when a wildcard import of {@code pkg} would resolve — the package dir exists on the classpath. */
    private static boolean packageResolvable(String pkg) {
        if (pkg.startsWith("java.")) {
            return true; // JDK packages are always present; no classpath resource to probe reliably
        }
        URL dir = Thread.currentThread().getContextClassLoader().getResource(pkg.replace('.', '/'));
        return dir != null;
    }

    /** True when a single-type import of {@code type} would resolve — its class file is on the classpath. */
    private static boolean typeResolvable(String type) {
        return Thread.currentThread().getContextClassLoader()
                .getResource(type.replace('.', '/') + ".class") != null;
    }

    // --- helpers ------------------------------------------------------------------------------

    private static String render(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                sb.append("  [").append(d.getLineNumber()).append("] ")
                        .append(d.getMessage(Locale.ENGLISH)).append('\n');
            }
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** An in-memory Java source for the compiler. */
    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(java.net.URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
