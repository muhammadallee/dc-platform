package com.acme.platform.build.enforcer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Covers the happy path per category plus every forbidden edge of the constitution. */
class PlatformLayerRuleTest {

    // ---------- fixtures ----------

    private static MavenProject project(String artifactId, String moduleDir, Dependency... dependencies) {
        Model model = new Model();
        model.setGroupId("com.acme.platform");
        model.setArtifactId(artifactId);
        for (Dependency d : dependencies) {
            model.addDependency(d);
        }
        MavenProject project = new MavenProject(model);
        project.setFile(new File(new File("repo", moduleDir), "pom.xml").getAbsoluteFile());
        project.setOriginalModel(model);
        return project;
    }

    private static Dependency platformDep(String artifactId) {
        Dependency d = new Dependency();
        d.setGroupId("com.acme.platform");
        d.setArtifactId(artifactId);
        return d;
    }

    private static Dependency testScoped(String artifactId) {
        Dependency d = platformDep(artifactId);
        d.setScope("test");
        return d;
    }

    private static Dependency thirdParty(String groupId, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        return d;
    }

    private static void assertPasses(MavenProject project) {
        assertThatCode(() -> new PlatformLayerRule(project).execute()).doesNotThrowAnyException();
    }

    private static void assertFailsWith(MavenProject project, String... messageParts) {
        assertThatThrownBy(() -> new PlatformLayerRule(project).execute())
                .isInstanceOf(EnforcerRuleException.class)
                .hasMessageContainingAll(messageParts);
    }

    // ---------- happy paths ----------

    @Nested
    class AllowedEdges {

        @Test
        void apiMayDependOnCoreApiOnly() {
            assertPasses(project("platform-messaging-api", "messaging/platform-messaging-api",
                    platformDep("platform-core-api")));
        }

        @Test
        void spiMayDependOnSameCapabilityApiAndCore() {
            assertPasses(project("platform-messaging-spi", "messaging/platform-messaging-spi",
                    platformDep("platform-messaging-api"), platformDep("platform-core-api")));
        }

        @Test
        void implMayDependOnSameCapabilitySpiAndApi() {
            assertPasses(project("platform-messaging-kafka", "messaging/platform-messaging-kafka",
                    platformDep("platform-messaging-spi"), platformDep("platform-messaging-api")));
        }

        @Test
        void autoconfigureMayDependOnSameCapabilityStackAndOtherCapabilitiesApi() {
            assertPasses(project("platform-messaging-autoconfigure", "messaging/platform-messaging-autoconfigure",
                    platformDep("platform-messaging-api"), platformDep("platform-messaging-spi"),
                    platformDep("platform-messaging-inmemory"), platformDep("platform-observability-api")));
        }

        @Test
        void starterMayDependOnSameCapabilityAutoconfigureAndImpl() {
            assertPasses(project("platform-starter-messaging-kafka", "messaging/platform-starter-messaging-kafka",
                    platformDep("platform-messaging-autoconfigure"), platformDep("platform-messaging-kafka")));
        }

        @Test
        void testScopedPlatformDependenciesAreExempt() {
            assertPasses(project("platform-messaging-api", "messaging/platform-messaging-api",
                    testScoped("platform-build-tools")));
        }

        @Test
        void buildModulesAreExemptIncludingLiteralVersions() {
            assertPasses(project("platform-build-tools", "build/platform-build-tools",
                    thirdParty("com.tngtech.archunit", "archunit", "1.4.0")));
        }

        @Test
        void examplesMayDependOnStarters() {
            assertPasses(project("example-rest-service", "examples/example-rest-service",
                    platformDep("platform-starter-messaging-kafka")));
        }
    }

    // ---------- forbidden edges ----------

    @Nested
    class ForbiddenEdges {

        @Test
        void apiMustNotDependOnAnythingButCoreApi() {
            assertFailsWith(project("platform-messaging-api", "messaging/platform-messaging-api",
                            platformDep("platform-messaging-spi")),
                    "api -> core-api only", "platform-messaging-spi");
        }

        @Test
        void spiMustNotDependOnAnotherCapabilitysApi() {
            assertFailsWith(project("platform-messaging-spi", "messaging/platform-messaging-spi",
                            platformDep("platform-cache-api")),
                    "spi -> same-capability api", "platform-cache-api");
        }

        @Test
        void implMustNotDependOnAnotherImpl() {
            assertFailsWith(project("platform-messaging-kafka", "messaging/platform-messaging-kafka",
                            platformDep("platform-messaging-inmemory")),
                    "impl -> impl", "platform-messaging-inmemory");
        }

        @Test
        void implMustNotDependOnAnotherCapability() {
            assertFailsWith(project("platform-messaging-kafka", "messaging/platform-messaging-kafka",
                            platformDep("platform-cache-api")),
                    "impl -> same-capability spi/api");
        }

        @Test
        void autoconfigureMustNotDependOnStarters() {
            assertFailsWith(project("platform-messaging-autoconfigure", "messaging/platform-messaging-autoconfigure",
                            platformDep("platform-starter-cache")),
                    "autoconfigure -> starter");
        }

        @Test
        void autoconfigureMustReachOtherCapabilitiesOnlyViaApi() {
            assertFailsWith(project("platform-messaging-autoconfigure", "messaging/platform-messaging-autoconfigure",
                            platformDep("platform-cache-caffeine")),
                    "only through their", "-api", "@ConditionalOnClass");
        }

        @Test
        void starterMustNotDependOnStarter() {
            assertFailsWith(project("platform-starter-messaging", "messaging/platform-starter-messaging",
                            platformDep("platform-starter-cache")),
                    "starter -> starter is forbidden",
                    "move the shared dependency into the autoconfigure module or the consumer's POM");
        }

        @Test
        void starterMustNotDependOnForeignAutoconfigure() {
            assertFailsWith(project("platform-starter-messaging", "messaging/platform-starter-messaging",
                            platformDep("platform-cache-autoconfigure")),
                    "starter -> same-capability autoconfigure");
        }

        @Test
        void testSupportMustNotDependOnStarters() {
            assertFailsWith(project("platform-messaging-test", "messaging/platform-messaging-test",
                            platformDep("platform-starter-messaging")),
                    "test-support ->", "never on", "starters");
        }
    }

    // ---------- fan-out ceilings ----------

    @Test
    void fanOutCeilingFailsWhenExceeded() {
        assertFailsWith(project("platform-messaging-autoconfigure", "messaging/platform-messaging-autoconfigure",
                        platformDep("platform-a-api"), platformDep("platform-b-api"), platformDep("platform-c-api"),
                        platformDep("platform-d-api"), platformDep("platform-e-api"), platformDep("platform-f-api"),
                        platformDep("platform-messaging-api")),
                "fan-out ceiling exceeded", "7", "at most 6");
    }

    // ---------- version hygiene ----------

    @Nested
    class VersionBan {

        @Test
        void literalDependencyVersionOutsideBuildFails() {
            assertFailsWith(project("platform-messaging-kafka", "messaging/platform-messaging-kafka",
                            thirdParty("org.apache.kafka", "kafka-clients", "3.7.0")),
                    "literal <version>", "no <version> outside build/", "platform-dependencies");
        }

        @Test
        void propertyExpressionVersionsAreAllowed() {
            assertPasses(project("platform-messaging-kafka", "messaging/platform-messaging-kafka",
                    thirdParty("com.acme.platform", "platform-messaging-api", "${revision}")));
        }

        @Test
        void literalPluginVersionOutsideBuildFails() {
            MavenProject project = project("platform-messaging-kafka", "messaging/platform-messaging-kafka");
            Plugin plugin = new Plugin();
            plugin.setGroupId("org.apache.maven.plugins");
            plugin.setArtifactId("maven-shade-plugin");
            plugin.setVersion("3.6.0");
            Build build = new Build();
            build.addPlugin(plugin);
            project.getOriginalModel().setBuild(build);
            assertFailsWith(project, "literal <version> on plugin", "pluginManagement");
        }
    }

    // ---------- category inference ----------

    @Test
    void categoryIsInferredFromArtifactIdAndPath() {
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-core-api"))
                .isEqualTo(PlatformLayerRule.Category.API);
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-messaging-spi"))
                .isEqualTo(PlatformLayerRule.Category.SPI);
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-messaging-autoconfigure"))
                .isEqualTo(PlatformLayerRule.Category.AUTOCONFIGURE);
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-starter-messaging"))
                .isEqualTo(PlatformLayerRule.Category.STARTER);
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-messaging-test"))
                .isEqualTo(PlatformLayerRule.Category.TEST_SUPPORT);
        assertThat(PlatformLayerRule.Category.ofArtifactId("tck-messaging"))
                .isEqualTo(PlatformLayerRule.Category.TEST_SUPPORT);
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-build-maven-plugin"))
                .isEqualTo(PlatformLayerRule.Category.BUILD);
        assertThat(PlatformLayerRule.Category.ofArtifactId("platform-messaging-kafka"))
                .isEqualTo(PlatformLayerRule.Category.IMPLEMENTATION);
        assertThat(PlatformLayerRule.Category.of(project("platform-parent", "build/platform-parent")))
                .isEqualTo(PlatformLayerRule.Category.BUILD);
        assertThat(PlatformLayerRule.Category.of(project("example-rest-service", "examples/example-rest-service")))
                .isEqualTo(PlatformLayerRule.Category.EXAMPLES);
    }

    @Test
    void capabilityIsExtractedFromArtifactId() {
        assertThat(PlatformLayerRule.capabilityOf("platform-messaging-api")).isEqualTo("messaging");
        assertThat(PlatformLayerRule.capabilityOf("platform-starter-messaging-kafka")).isEqualTo("messaging");
        assertThat(PlatformLayerRule.capabilityOf("platform-core-api")).isEqualTo("core");
    }
}
