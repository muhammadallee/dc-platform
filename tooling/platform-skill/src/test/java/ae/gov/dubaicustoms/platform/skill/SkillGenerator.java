package ae.gov.dubaicustoms.platform.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the Claude Skill bundle from {@code docs/reference/platform-index.json} at build time
 * (phase-16 D.1): {@code SKILL.md} (a trigger-tuned description + capability/starter map) plus
 * condensed {@code resources/} guides. Everything is derived from the index, so the skill cannot rot;
 * a staleness test asserts the {@code SKILL.md} version equals the train version.
 */
final class SkillGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillGenerator() {
    }

    /** The reactor root (the dir holding {@code build/platform-bom}). */
    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("build/platform-bom/pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate the reactor root above " + Path.of("").toAbsolutePath());
    }

    /** {@code docs/reference/platform-index.json} at the reactor root. */
    static Path indexJson() {
        Path candidate = repoRoot().resolve("docs/reference/platform-index.json");
        if (!Files.exists(candidate)) {
            throw new IllegalStateException("platform-index.json not found at " + candidate
                    + " — run a build that generates the index first");
        }
        return candidate;
    }

    /** The module's committed {@code skill/} output directory (what the assembly zips). */
    static Path skillDir() {
        return repoRoot().resolve("tooling/platform-skill/skill");
    }

    /** Generates {@code SKILL.md} and {@code resources/} into {@code outDir}; returns the SKILL.md path. */
    static Path generate(Path indexJson, Path outDir) {
        JsonNode index = read(indexJson);
        String version = index.path("version").asText("unknown");
        write(outDir.resolve("SKILL.md"), skillMarkdown(index, version));
        write(outDir.resolve("resources/capabilities.md"), capabilities(index));
        write(outDir.resolve("resources/property-cheatsheet.md"), propertyCheatSheet(index));
        return outDir.resolve("SKILL.md");
    }

    private static String skillMarkdown(JsonNode index, String version) {
        StringBuilder names = new StringBuilder();
        index.path("capabilities").forEach(c -> names.append(c.path("name").asText()).append(", "));
        String capabilityNames = names.length() > 2 ? names.substring(0, names.length() - 2) : "";

        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("name: dc-platform\n");
        md.append("description: Use when building or modifying a service on the DC Platform (the internal ")
                .append("Spring Boot chassis). Triggers on \"DC platform\", capability names (")
                .append(capabilityNames).append("), platform-starter-* ids, and dc.platform.* properties. ")
                .append("Explains which starter to add, the config keys, and the error codes. ")
                .append("Train version ").append(version).append(".\n");
        md.append("---\n\n");
        md.append("# DC Platform\n\n");
        md.append("The DC Platform is a Spring Boot chassis. A service imports one BOM and adds only the ")
                .append("starters it needs; every capability is auto-configured with a kill switch under ")
                .append("`dc.platform.<cap>.enabled`. Version `").append(version).append("`.\n\n");
        md.append("## How to use\n\n");
        md.append("1. Pick the capability for the task (see `resources/capabilities.md`).\n");
        md.append("2. Add its `platform-starter-*` — never the wrapped library directly (the service ")
                .append("parent bans that).\n");
        md.append("3. Configure via `dc.platform.*` (see `resources/property-cheatsheet.md`).\n");
        md.append("4. For live, authoritative facts query the platform MCP server ")
                .append("(`platform-mcp-server --stdio`).\n");
        return md.toString();
    }

    private static String capabilities(JsonNode index) {
        StringBuilder md = new StringBuilder("# Capabilities\n\n| Capability | Starter(s) | Summary |\n");
        md.append("|------------|-----------|---------|\n");
        index.path("capabilities").forEach(c -> {
            StringBuilder starters = new StringBuilder();
            c.path("starters").forEach(s -> starters.append('`').append(shortId(s.asText())).append("` "));
            md.append("| ").append(c.path("name").asText()).append(" | ").append(starters.toString().trim())
                    .append(" | ").append(c.path("oneLiner").asText().replace("|", "\\|")).append(" |\n");
        });
        return md.toString();
    }

    private static String propertyCheatSheet(JsonNode index) {
        StringBuilder md = new StringBuilder("# Property cheat sheet\n\n| Key | Type | Default |\n");
        md.append("|-----|------|---------|\n");
        index.path("properties").forEach(p -> md.append("| `").append(p.path("name").asText()).append("` | ")
                .append(p.path("type").asText()).append(" | `").append(p.path("default").asText()).append("` |\n"));
        return md.toString();
    }

    private static String shortId(String coord) {
        int colon = coord.lastIndexOf(':');
        return colon < 0 ? coord : coord.substring(colon + 1);
    }

    private static JsonNode read(Path indexJson) {
        try {
            return MAPPER.readTree(indexJson.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
