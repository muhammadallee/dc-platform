package com.acme.platform.build.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewModuleMojoTest {

    @TempDir
    Path root;

    @BeforeEach
    void miniReactor() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<modules>\n</modules>\n");
        Files.createDirectories(root.resolve("build/platform-bom"));
        Files.writeString(root.resolve("build/platform-bom/pom.xml"),
                "<dependencies>\n</dependencies>\n");
    }

    private NewModuleMojo mojo(String capability, String kind, String provider) {
        NewModuleMojo mojo = new NewModuleMojo();
        mojo.capability = capability;
        mojo.kind = kind;
        mojo.provider = provider;
        mojo.rootDirectory = root.toFile();
        return mojo;
    }

    @Test
    void executeGeneratesAndRegistersTheModule() throws MojoExecutionException {
        mojo("scratch", "api", null).execute();

        assertThat(root.resolve("scratch/platform-scratch-api/pom.xml")).exists();
    }

    @Test
    void executeWrapsGenerationErrorsInMojoExecutionException() {
        assertThatThrownBy(() -> mojo("scratch", "nope", null).execute())
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("new-module failed");
    }
}
