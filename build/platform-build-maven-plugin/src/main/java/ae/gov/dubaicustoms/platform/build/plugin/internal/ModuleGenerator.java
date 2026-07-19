package ae.gov.dubaicustoms.platform.build.plugin.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// Generates constitution-compliant module skeletons from the templates under /templates and the
// ArchConstitutionTest template shipped inside platform-build-tools.
public final class ModuleGenerator {

    /** What was generated, for logging and for tests. */
    public record GeneratedModule(Path moduleDir, String artifactId, String modulePath) {
    }

    private enum Kind {
        API, SPI, IMPL, AUTOCONFIGURE, STARTER, TEST;

        static Kind parse(String raw) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown kind '" + raw + "': expected one of api|spi|impl|autoconfigure|starter|test");
            }
        }
    }

    private final Path rootDir;

    public ModuleGenerator(Path rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * Generates the module, then registers it in the root {@code <modules>} and in platform-bom.
     *
     * @throws IllegalArgumentException on bad parameters
     * @throws IOException              when the module already exists or files cannot be written
     */
    public GeneratedModule generate(String capability, String rawKind, String provider) throws IOException {
        if (capability == null || !capability.matches("[a-z][a-z0-9]*")) {
            throw new IllegalArgumentException("capability must match [a-z][a-z0-9]*, got: " + capability);
        }
        Kind kind = Kind.parse(rawKind);
        if (kind == Kind.IMPL && (provider == null || provider.isBlank())) {
            throw new IllegalArgumentException("kind=impl requires -Dprovider=<provider>");
        }

        String artifactId = artifactId(capability, kind, provider);
        String modulePath = capability + "/" + artifactId;
        Path moduleDir = rootDir.resolve(capability).resolve(artifactId);
        if (Files.exists(moduleDir)) {
            throw new IOException("module directory already exists: " + moduleDir);
        }

        String basePackage = basePackage(capability, kind, provider);
        write(moduleDir.resolve("pom.xml"),
                render(template("/templates/pom-" + kind.name().toLowerCase(Locale.ROOT) + ".xml"),
                        capability, artifactId, provider, basePackage));

        if (kind != Kind.STARTER) { // starters are POM-only by constitution
            String pkgDir = basePackage.replace('.', '/');
            write(moduleDir.resolve("src/main/java/" + pkgDir + "/package-info.java"),
                    render(template("/templates/package-info.java.template"),
                            capability, artifactId, provider, basePackage));
            write(moduleDir.resolve("src/test/java/" + pkgDir + "/ArchConstitutionTest.java"),
                    render(template("/arch/ArchConstitutionTest.java.template"),
                            capability, artifactId, provider, basePackage));
        }
        if (kind == Kind.AUTOCONFIGURE) {
            String pkgDir = basePackage.replace('.', '/');
            String cap = pascal(capability);
            write(moduleDir.resolve("src/main/java/" + pkgDir + "/" + cap + "Properties.java"),
                    render(template("/templates/Properties.java.template"),
                            capability, artifactId, provider, basePackage));
            write(moduleDir.resolve("src/main/java/" + pkgDir + "/" + cap + "AutoConfiguration.java"),
                    render(template("/templates/AutoConfiguration.java.template"),
                            capability, artifactId, provider, basePackage));
            write(moduleDir.resolve("src/test/java/" + pkgDir + "/" + cap + "AutoConfigurationTest.java"),
                    render(template("/templates/AutoConfigurationTest.java.template"),
                            capability, artifactId, provider, basePackage));
            write(moduleDir.resolve(
                            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"),
                    basePackage + "." + cap + "AutoConfiguration\n");
        }

        PomEditor.appendModule(rootDir.resolve("pom.xml"), modulePath);
        PomEditor.appendBomEntry(rootDir.resolve("build/platform-bom/pom.xml"), artifactId);
        return new GeneratedModule(moduleDir, artifactId, modulePath);
    }

    static String artifactId(String capability, Kind kind, String provider) {
        return switch (kind) {
            case API -> "platform-" + capability + "-api";
            case SPI -> "platform-" + capability + "-spi";
            case AUTOCONFIGURE -> "platform-" + capability + "-autoconfigure";
            case TEST -> "platform-" + capability + "-test";
            case IMPL -> "platform-" + capability + "-" + provider;
            case STARTER -> "platform-starter-" + capability
                    + (provider == null || provider.isBlank() ? "" : "-" + provider);
        };
    }

    static String basePackage(String capability, Kind kind, String provider) {
        String root = "ae.gov.dubaicustoms.platform." + capability;
        return switch (kind) {
            case API, STARTER -> root;
            case SPI -> root + ".spi";
            case AUTOCONFIGURE -> root + ".autoconfigure";
            case TEST -> root + ".testing";
            case IMPL -> root + "." + provider;
        };
    }

    private String render(String template, String capability, String artifactId, String provider,
            String basePackage) {
        return template
                .replace("@capability@", capability)
                .replace("@Cap@", pascal(capability))
                .replace("@artifactId@", artifactId)
                .replace("@provider@", provider == null ? "" : provider)
                .replace("@package@", basePackage)
                .replace("${package}", basePackage); // ArchConstitutionTest template token
    }

    private static String pascal(String capability) {
        return Character.toUpperCase(capability.charAt(0)) + capability.substring(1);
    }

    private String template(String resource) throws IOException {
        try (InputStream in = ModuleGenerator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("template resource not found on plugin classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
