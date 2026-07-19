package com.acme.platform.build.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomEditorTest {

    @TempDir
    Path dir;

    @Test
    void appendsModuleBeforeClosingTagAndIsIdempotent() throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <modules>
                    <module>build/platform-parent</module>
                  </modules>
                """);

        PomEditor.appendModule(pom, "scratch/platform-scratch-api");
        PomEditor.appendModule(pom, "scratch/platform-scratch-api");

        String content = Files.readString(pom);
        assertThat(content).containsOnlyOnce("<module>scratch/platform-scratch-api</module>");
        assertThat(content.indexOf("scratch/platform-scratch-api"))
                .isLessThan(content.indexOf("</modules>"));
    }

    @Test
    void failsWhenModulesSectionMissing() throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, "<project/>\n");
        assertThatThrownBy(() -> PomEditor.appendModule(pom, "x/y"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("<modules>");
    }

    @Test
    void appendsBomEntryAfterMarkerAndIsIdempotent() throws IOException {
        Path bom = dir.resolve("bom.xml");
        Files.writeString(bom, """
                <dependencies>
                  <!-- ==== platform artifacts (appended per phase) ==== -->
                </dependencies>
                """);

        PomEditor.appendBomEntry(bom, "platform-scratch-api");
        PomEditor.appendBomEntry(bom, "platform-scratch-api");

        String content = Files.readString(bom);
        assertThat(content).containsOnlyOnce("<artifactId>platform-scratch-api</artifactId>");
        assertThat(content).contains("<version>${revision}</version>");
        assertThat(content.indexOf("==== platform artifacts"))
                .isLessThan(content.indexOf("platform-scratch-api"));
    }

    @Test
    void fallsBackToClosingDependenciesWhenMarkerMissing() throws IOException {
        Path bom = dir.resolve("bom.xml");
        Files.writeString(bom, """
                <dependencies>
                </dependencies>
                """);

        PomEditor.appendBomEntry(bom, "platform-scratch-api");

        assertThat(Files.readString(bom)).contains("<artifactId>platform-scratch-api</artifactId>");
    }
}
