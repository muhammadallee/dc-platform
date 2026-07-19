package com.acme.platform.build.plugin.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Line-based POM editing on purpose: a model round-trip would destroy the hand-written comments
// that the coding standards require in every build POM.
public final class PomEditor {

    private static final String BOM_MARKER = "==== platform artifacts";

    private PomEditor() {
    }

    /** Appends a module entry before {@code </modules>} in the root aggregator; idempotent. */
    public static void appendModule(Path rootPom, String modulePath) throws IOException {
        List<String> lines = Files.readAllLines(rootPom);
        String entry = "    <module>" + modulePath + "</module>";
        if (lines.stream().anyMatch(l -> l.contains("<module>" + modulePath + "</module>"))) {
            return;
        }
        int closing = indexOfFirst(lines, "</modules>");
        if (closing < 0) {
            throw new IOException("no <modules> section found in " + rootPom);
        }
        List<String> result = new ArrayList<>(lines);
        result.add(closing, entry);
        Files.write(rootPom, result);
    }

    /** Appends a platform artifact entry to platform-bom's dependencyManagement; idempotent. */
    public static void appendBomEntry(Path bomPom, String artifactId) throws IOException {
        List<String> lines = Files.readAllLines(bomPom);
        if (lines.stream().anyMatch(l -> l.contains("<artifactId>" + artifactId + "</artifactId>"))) {
            return;
        }
        String entry = "      <dependency><groupId>com.acme.platform</groupId><artifactId>" + artifactId
                + "</artifactId><version>${revision}</version></dependency>";
        int marker = indexOfFirst(lines, BOM_MARKER);
        int insertAt;
        if (marker >= 0) {
            insertAt = marker + 1;
        } else {
            insertAt = indexOfFirst(lines, "</dependencies>");
            if (insertAt < 0) {
                throw new IOException("no dependencyManagement <dependencies> found in " + bomPom);
            }
        }
        List<String> result = new ArrayList<>(lines);
        result.add(insertAt, entry);
        Files.write(bomPom, result);
    }

    private static int indexOfFirst(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
