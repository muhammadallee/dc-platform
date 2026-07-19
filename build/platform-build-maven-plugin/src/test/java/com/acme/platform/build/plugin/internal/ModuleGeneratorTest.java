package com.acme.platform.build.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generates into a temp mini-reactor and asserts structure, content, and registration. */
class ModuleGeneratorTest {

    @TempDir
    Path root;

    @BeforeEach
    void miniReactor() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modules>
                    <module>build/platform-parent</module>
                  </modules>
                </project>
                """);
        Path bomDir = root.resolve("build/platform-bom");
        Files.createDirectories(bomDir);
        Files.writeString(bomDir.resolve("pom.xml"), """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <!-- ==== platform artifacts (appended per phase) ==== -->
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
    }

    @Test
    void generatesApiModuleWithArchTestAndRegistersIt() throws IOException {
        var generated = new ModuleGenerator(root).generate("scratch", "api", null);

        assertThat(generated.artifactId()).isEqualTo("platform-scratch-api");
        assertThat(generated.modulePath()).isEqualTo("scratch/platform-scratch-api");
        Path module = root.resolve("scratch/platform-scratch-api");
        assertThat(module.resolve("pom.xml")).exists();
        assertThat(module.resolve("src/main/java/com/acme/platform/scratch/package-info.java")).exists();
        assertThat(module.resolve("src/test/java/com/acme/platform/scratch/ArchConstitutionTest.java")).exists();

        String pom = Files.readString(module.resolve("pom.xml"));
        assertThat(pom)
                .contains("<artifactId>platform-scratch-api</artifactId>")
                .contains("<artifactId>platform-parent</artifactId>")
                .contains("../../build/platform-parent")
                .doesNotContain("@capability@").doesNotContain("@Cap@").doesNotContain("@artifactId@");

        String archTest = Files.readString(
                module.resolve("src/test/java/com/acme/platform/scratch/ArchConstitutionTest.java"));
        assertThat(archTest)
                .startsWith("package com.acme.platform.scratch;")
                .contains("PlatformArchRules.all()");

        assertThat(Files.readString(root.resolve("pom.xml")))
                .contains("<module>scratch/platform-scratch-api</module>");
        assertThat(Files.readString(root.resolve("build/platform-bom/pom.xml")))
                .contains("<artifactId>platform-scratch-api</artifactId>");
    }

    @Test
    void generatesAutoconfigureModuleWithCanonicalFiles() throws IOException {
        new ModuleGenerator(root).generate("scratch", "autoconfigure", null);

        Path module = root.resolve("scratch/platform-scratch-autoconfigure");
        Path pkg = module.resolve("src/main/java/com/acme/platform/scratch/autoconfigure");
        assertThat(pkg.resolve("ScratchProperties.java")).exists();
        assertThat(pkg.resolve("ScratchAutoConfiguration.java")).exists();
        assertThat(module.resolve(
                "src/test/java/com/acme/platform/scratch/autoconfigure/ScratchAutoConfigurationTest.java")).exists();
        assertThat(module.resolve("src/main/resources/META-INF/spring/"
                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
                .hasContent("com.acme.platform.scratch.autoconfigure.ScratchAutoConfiguration");

        String autoConfiguration = Files.readString(pkg.resolve("ScratchAutoConfiguration.java"));
        assertThat(autoConfiguration)
                .contains("prefix = \"acme.platform.scratch\"")
                .contains("matchIfMissing = true")
                .contains("@EnableConfigurationProperties(ScratchProperties.class)")
                .contains("Activates when:");
    }

    @Test
    void generatesImplModuleUsingProviderPackage() throws IOException {
        var generated = new ModuleGenerator(root).generate("messaging", "impl", "kafka");

        assertThat(generated.artifactId()).isEqualTo("platform-messaging-kafka");
        assertThat(root.resolve("messaging/platform-messaging-kafka/src/main/java/"
                + "com/acme/platform/messaging/kafka/package-info.java")).exists();
    }

    @Test
    void generatesStarterAsPomOnly() throws IOException {
        var generated = new ModuleGenerator(root).generate("messaging", "starter", "kafka");

        assertThat(generated.artifactId()).isEqualTo("platform-starter-messaging-kafka");
        Path module = root.resolve("messaging/platform-starter-messaging-kafka");
        assertThat(module.resolve("pom.xml")).exists();
        assertThat(module.resolve("src")).doesNotExist(); // starters contain no code
    }

    @Test
    void generatesTestSupportModuleInTestingPackage() throws IOException {
        new ModuleGenerator(root).generate("messaging", "test", null);

        assertThat(root.resolve("messaging/platform-messaging-test/src/main/java/"
                + "com/acme/platform/messaging/testing/package-info.java")).exists();
    }

    @Test
    void rejectsUnknownKind() {
        assertThatThrownBy(() -> new ModuleGenerator(root).generate("scratch", "gateway", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api|spi|impl|autoconfigure|starter|test");
    }

    @Test
    void rejectsImplWithoutProvider() {
        assertThatThrownBy(() -> new ModuleGenerator(root).generate("scratch", "impl", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void rejectsInvalidCapabilityName() {
        assertThatThrownBy(() -> new ModuleGenerator(root).generate("Bad-Name", "api", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a-z][a-z0-9]*");
    }

    @Test
    void rejectsExistingModuleDirectory() throws IOException {
        new ModuleGenerator(root).generate("scratch", "api", null);
        assertThatThrownBy(() -> new ModuleGenerator(root).generate("scratch", "api", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("already exists");
    }
}
