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
 * Generates {@code docs/llms.txt} and {@code docs/llms-full.txt} from the MkDocs {@code nav} (per
 * llmstxt.org): a curated, link-first map of the docs an agent can fetch, plus a single concatenated
 * full-text file. The nav is the single source, so the files cannot drift from the site; all pages
 * stay clean Markdown. Sections carry a Diátaxis label (Quickstart=tutorial, Capabilities=how-to +
 * reference, Concepts=explanation) so agents can pick the right kind of page (phase-16 D.3).
 */
final class LlmsTxtGenerator {

    private static final String TITLE = "DC Platform";
    private static final String SUMMARY =
            "Internal Spring Boot chassis (\"platform\"): capability starters, references, and decisions. "
                    + "Add one BOM + the starters you need; everything is auto-configured with kill switches "
                    + "under dc.platform.*.";

    /** Diátaxis hint appended to a nav section title in llms.txt. */
    private static final Map<String, String> DIATAXIS = Map.of(
            "Quickstart", "tutorial",
            "Concepts", "explanation",
            "Capabilities", "how-to + reference",
            "Reference", "reference",
            "Runbooks", "how-to",
            "Operations", "how-to",
            "Decisions", "explanation");

    private LlmsTxtGenerator() {
    }

    /** A single nav entry: its section, human title, and doc path (relative to {@code docs/}). */
    private record Entry(String section, String title, String path) {
    }

    /** Regenerates both files; returns the {@code llms.txt} path. */
    static Path generate() {
        List<Entry> nav = parseNav();
        Path docs = PlatformDocs.docsRoot();
        writeIndex(nav, docs.resolve("llms.txt"));
        writeFull(nav, docs.resolve("llms-full.txt"));
        return docs.resolve("llms.txt");
    }

    private static void writeIndex(List<Entry> nav, Path out) {
        Map<String, List<Entry>> bySection = new LinkedHashMap<>();
        for (Entry e : nav) {
            bySection.computeIfAbsent(e.section(), s -> new ArrayList<>()).add(e);
        }
        StringBuilder md = new StringBuilder();
        md.append("# ").append(TITLE).append("\n\n");
        md.append("> ").append(SUMMARY).append("\n\n");
        md.append("Generated from the docs navigation. Each link is a clean Markdown page.\n");
        bySection.forEach((section, entries) -> {
            String diataxis = DIATAXIS.get(section);
            md.append("\n## ").append(section);
            if (diataxis != null) {
                md.append(" (").append(diataxis).append(')');
            }
            md.append("\n\n");
            for (Entry e : entries) {
                md.append("- [").append(e.title()).append("](").append(e.path()).append(")\n");
            }
        });
        PlatformDocs.writeString(out, md.toString());
    }

    private static void writeFull(List<Entry> nav, Path out) {
        StringBuilder full = new StringBuilder();
        full.append("# ").append(TITLE).append(" — full documentation\n\n");
        full.append("> ").append(SUMMARY).append("\n");
        Path docs = PlatformDocs.docsRoot();
        for (Entry e : nav) {
            Path page = docs.resolve(e.path());
            if (!Files.exists(page)) {
                continue;
            }
            try {
                full.append("\n\n---\n\n");
                full.append("<!-- source: ").append(e.path()).append(" -->\n\n");
                full.append(Files.readString(page).strip()).append('\n');
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        PlatformDocs.writeString(out, full.toString());
    }

    private static final Pattern LEAF = Pattern.compile("^(\\s*)-\\s+(?:\"([^\"]+)\"|([^:]+)):\\s*(\\S+\\.md)\\s*$");
    private static final Pattern SECTION = Pattern.compile("^(\\s*)-\\s+([^:]+):\\s*$");

    /** Parses the {@code nav:} block of {@code mkdocs.yml} into flat (section, title, path) entries. */
    private static List<Entry> parseNav() {
        Path mkdocs = PlatformDocs.repoRoot().resolve("mkdocs.yml");
        List<Entry> entries = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(mkdocs);
            boolean inNav = false;
            String currentSection = "Home";
            for (String line : lines) {
                if (line.strip().equals("nav:")) {
                    inNav = true;
                    continue;
                }
                if (!inNav) {
                    continue;
                }
                if (!line.startsWith(" ") && !line.isBlank()) {
                    break; // next top-level key ends the nav block
                }
                Matcher leaf = LEAF.matcher(line);
                if (leaf.matches()) {
                    String title = leaf.group(2) != null ? leaf.group(2) : leaf.group(3).trim();
                    String path = leaf.group(4).trim();
                    boolean topLevel = leaf.group(1).length() <= 2;
                    entries.add(new Entry(topLevel ? title : currentSection, title, path));
                    continue;
                }
                Matcher section = SECTION.matcher(line);
                if (section.matches()) {
                    currentSection = section.group(2).trim();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }
}
