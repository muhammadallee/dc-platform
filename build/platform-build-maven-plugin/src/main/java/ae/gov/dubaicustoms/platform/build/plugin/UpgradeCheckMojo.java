package ae.gov.dubaicustoms.platform.build.plugin;

import ae.gov.dubaicustoms.platform.build.plugin.internal.DeprecatedProperty;
import ae.gov.dubaicustoms.platform.build.plugin.internal.MetadataDeprecations;
import ae.gov.dubaicustoms.platform.build.plugin.internal.PropertyKeyCollector;
import ae.gov.dubaicustoms.platform.build.plugin.internal.UpgradeReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;

/**
 * Reports what changes when a service upgrades to a target platform version, without touching the
 * service's sources. It (1) resolves the target {@code platform-bom} and diffs its managed versions
 * against the project's current ones, and (2) scans the project's {@code application*} config for
 * keys deprecated in the target version (read from the target platform jars'
 * {@code spring-configuration-metadata.json}). Output: a console summary + {@code
 * target/platform-upgrade-report.md} linking the train release notes.
 *
 * <p>Usage: {@code mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check
 * -Dplatform.target=0.3.0} from a service's directory. v1 scope is version diff + property
 * deprecation scan; binary-compatibility (japicmp) checks are future work.
 *
 * @since 0.2.0
 */
@Mojo(name = "upgrade-check", requiresProject = true, threadSafe = true)
public class UpgradeCheckMojo extends AbstractMojo {

    private static final String PLATFORM_GROUP = "ae.gov.dubaicustoms.platform";
    private static final String PLATFORM_BOM = "platform-bom";
    private static final String METADATA_ENTRY = "META-INF/spring-configuration-metadata.json";
    private static final String DOCS_BASE = "https://platform.dubaicustoms.gov.ae/docs";

    /** Target platform version to upgrade to, e.g. {@code 0.3.0}. */
    @Parameter(property = "platform.target", required = true)
    String target;

    /** Overridable link to the release notes for the target train (default: platform docs). */
    @Parameter(property = "platform.releaseNotesUrl")
    String releaseNotesUrl;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Component
    ProjectBuilder projectBuilder;

    @Component
    RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException {
        MavenProject targetBom = buildTargetBom();
        Map<String, String> targetManaged = managedVersions(targetBom.getDependencyManagement());
        Map<String, String> currentManaged = currentManagedVersions();

        Set<String> usedKeys = collectPropertyKeys();
        List<DeprecatedProperty> deprecations = readTargetDeprecations(targetBom.getDependencyManagement());

        String notes = releaseNotesUrl != null ? releaseNotesUrl : DOCS_BASE + "/upgrade/" + target;
        UpgradeReport report = UpgradeReport.of(
                currentManaged, targetManaged, usedKeys, deprecations, target, notes);

        writeReport(report);
        getLog().info(report.toConsoleSummary());
        if (report.hasDeprecationHits()) {
            getLog().warn("upgrade-check: " + report.deprecationHits().size()
                    + " configured propert(y/ies) are deprecated in " + target + " — fix before upgrading.");
        }
    }

    private MavenProject buildTargetBom() throws MojoExecutionException {
        Artifact bom = repositorySystem.createProjectArtifact(PLATFORM_GROUP, PLATFORM_BOM, target);
        ProjectBuildingRequest request = new org.apache.maven.project.DefaultProjectBuildingRequest(
                session.getProjectBuildingRequest());
        request.setProcessPlugins(false);
        request.setResolveDependencies(false);
        try {
            return projectBuilder.build(bom, request).getProject();
        } catch (ProjectBuildingException e) {
            throw new MojoExecutionException("upgrade-check: cannot resolve " + PLATFORM_GROUP + ":" + PLATFORM_BOM
                    + ":" + target + " — is the target version available in your repositories?", e);
        }
    }

    private Map<String, String> managedVersions(DependencyManagement dependencyManagement) {
        Map<String, String> versions = new LinkedHashMap<>();
        if (dependencyManagement == null) {
            return versions;
        }
        for (Dependency d : dependencyManagement.getDependencies()) {
            if (d.getVersion() != null && !d.getVersion().contains("${") && !"import".equals(d.getScope())) {
                versions.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
            }
        }
        return versions;
    }

    private Map<String, String> currentManagedVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        for (Map.Entry<String, Artifact> entry : project.getManagedVersionMap().entrySet()) {
            Artifact artifact = entry.getValue();
            versions.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion());
        }
        return versions;
    }

    private Set<String> collectPropertyKeys() throws MojoExecutionException {
        Path resources = project.getBasedir().toPath().resolve("src").resolve("main").resolve("resources");
        try {
            return PropertyKeyCollector.collect(resources);
        } catch (IOException e) {
            throw new MojoExecutionException("upgrade-check: failed to scan " + resources, e);
        }
    }

    // Best-effort: resolve each platform jar managed by the target BOM and read its deprecation
    // metadata. A jar that will not resolve (offline, not yet published) is logged and skipped rather
    // than failing the goal — a partial deprecation scan still helps.
    private List<DeprecatedProperty> readTargetDeprecations(DependencyManagement dependencyManagement) {
        List<DeprecatedProperty> deprecations = new ArrayList<>();
        if (dependencyManagement == null) {
            return deprecations;
        }
        for (Dependency d : dependencyManagement.getDependencies()) {
            if (!PLATFORM_GROUP.equals(d.getGroupId()) || d.getVersion() == null
                    || d.getVersion().contains("${") || "import".equals(d.getScope())
                    || !isJar(d.getType())) {
                continue;
            }
            Artifact jar = repositorySystem.createArtifact(
                    d.getGroupId(), d.getArtifactId(), d.getVersion(), "jar");
            deprecations.addAll(deprecationsIn(jar));
        }
        return deprecations;
    }

    private static boolean isJar(String type) {
        return type == null || type.isEmpty() || "jar".equals(type);
    }

    private List<DeprecatedProperty> deprecationsIn(Artifact jar) {
        ArtifactResolutionRequest request = new ArtifactResolutionRequest()
                .setArtifact(jar)
                .setLocalRepository(session.getLocalRepository())
                .setRemoteRepositories(project.getRemoteArtifactRepositories());
        repositorySystem.resolve(request);
        if (jar.getFile() == null || !jar.getFile().isFile()) {
            getLog().debug("upgrade-check: skipping unresolved " + jar);
            return List.of();
        }
        try (ZipFile zip = new ZipFile(jar.getFile())) {
            ZipEntry entry = zip.getEntry(METADATA_ENTRY);
            if (entry == null) {
                return List.of();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return MetadataDeprecations.parse(in);
            }
        } catch (IOException e) {
            getLog().warn("upgrade-check: could not read metadata from " + jar + ": " + e.getMessage());
            return List.of();
        }
    }

    private void writeReport(UpgradeReport report) throws MojoExecutionException {
        Path out = project.getBuild().getDirectory() == null
                ? project.getBasedir().toPath().resolve("target").resolve("platform-upgrade-report.md")
                : Path.of(project.getBuild().getDirectory()).resolve("platform-upgrade-report.md");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, report.toMarkdown(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("upgrade-check: failed to write " + out, e);
        }
    }
}
