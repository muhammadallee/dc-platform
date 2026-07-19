package ae.gov.dubaicustoms.platform.build.plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Fails the build when a publishable reactor artifact is missing from platform-bom's
 * dependencyManagement. Publishable = every reactor artifact except pom-packaging modules
 * (parents/BOMs/aggregator), the maven-plugin itself, and {@code example-*} modules.
 *
 * <p>Usage: {@code mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:<version>:check-bom} from the
 * repo root (also wired as a CI step; see decision D2 for why it is not bound to aggregator verify).
 *
 * @since 0.1.0
 */
@Mojo(name = "check-bom", aggregator = true, defaultPhase = LifecyclePhase.VERIFY)
public class CheckBomMojo extends AbstractMojo {

    // Package-private for tests (mojo parameter injection has no constructor).
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    List<MavenProject> reactorProjects;

    @Override
    public void execute() throws MojoFailureException {
        MavenProject bom = reactorProjects.stream()
                .filter(p -> "platform-bom".equals(p.getArtifactId()))
                .findFirst()
                .orElseThrow(() -> new MojoFailureException(
                        "platform-bom is not in the reactor; run check-bom from the repo root"));

        Set<String> managed = new HashSet<>();
        if (bom.getOriginalModel().getDependencyManagement() != null) {
            for (Dependency d : bom.getOriginalModel().getDependencyManagement().getDependencies()) {
                managed.add(d.getArtifactId());
            }
        }

        List<String> missing = new ArrayList<>();
        for (MavenProject p : reactorProjects) {
            if (!"pom".equals(p.getPackaging()) && !"maven-plugin".equals(p.getPackaging())
                    && !p.getArtifactId().startsWith("example-") && !managed.contains(p.getArtifactId())) {
                missing.add(p.getArtifactId());
            }
        }
        if (!missing.isEmpty()) {
            throw new MojoFailureException("check-bom: platform-bom is missing " + missing + ". Fix: add "
                    + "<dependency><groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>...</artifactId>"
                    + "<version>${revision}</version></dependency> for each, in the same PR that adds the module "
                    + "(reference/module-checklist.md).");
        }
        getLog().info("check-bom: all publishable reactor artifacts are managed in platform-bom.");
    }
}
