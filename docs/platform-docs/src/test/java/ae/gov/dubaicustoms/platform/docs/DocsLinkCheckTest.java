package ae.gov.dubaicustoms.platform.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pure-JVM broken-link gate over {@code docs/**&#47;*.md}: every relative Markdown link to another
 * {@code .md} file must resolve on disk. Runs with no network (unlike lychee/mkdocs --strict), so it
 * stays in the default reactor while the full HTML render remains the opt-in {@code -Pdocs-site}
 * profile. External URLs, in-page anchors, and non-Markdown asset links are out of scope here.
 */
class DocsLinkCheckTest {

    private static final Pattern LINK = Pattern.compile("\\]\\(([^)]+)\\)");

    @Test
    void noBrokenRelativeMarkdownLinks() throws IOException {
        Path docs = PlatformDocs.docsRoot();
        List<String> broken = new ArrayList<>();

        try (Stream<Path> tree = Files.walk(docs)) {
            tree.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/site/"))
                    .forEach(md -> checkFile(md, broken));
        }

        assertThat(broken)
                .as("broken relative markdown links found:\n%s", String.join("\n", broken))
                .isEmpty();
    }

    private static void checkFile(Path md, List<String> broken) {
        String content;
        try {
            content = Files.readString(md);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher m = LINK.matcher(content);
        while (m.find()) {
            String target = m.group(1).trim();
            // Only vet relative links to markdown files; skip code-fence noise, URLs, anchors, assets.
            if (target.isEmpty() || target.contains(" ") || target.contains("`") || target.contains("|")
                    || target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("mailto:") || target.startsWith("#")) {
                continue;
            }
            String pathPart = target.split("#", 2)[0];
            if (!pathPart.endsWith(".md")) {
                continue;
            }
            Path resolved = md.getParent().resolve(pathPart).normalize();
            if (!Files.exists(resolved)) {
                broken.add(PlatformDocs.repoRoot().relativize(md) + " → " + target);
            }
        }
    }
}
