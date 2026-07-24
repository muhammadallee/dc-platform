package ae.gov.dubaicustoms.platform.build.plugin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class CheckBomMojoTest {

    private static MavenProject project(String artifactId, String packaging) {
        Model model = new Model();
        model.setGroupId("ae.gov.dubaicustoms.platform");
        model.setArtifactId(artifactId);
        MavenProject project = new MavenProject(model);
        project.setPackaging(packaging);
        project.setOriginalModel(model);
        return project;
    }

    private static MavenProject bom(String... managedArtifactIds) {
        MavenProject bom = project("platform-bom", "pom");
        DependencyManagement dm = new DependencyManagement();
        for (String artifactId : managedArtifactIds) {
            Dependency d = new Dependency();
            d.setGroupId("ae.gov.dubaicustoms.platform");
            d.setArtifactId(artifactId);
            dm.addDependency(d);
        }
        bom.getOriginalModel().setDependencyManagement(dm);
        return bom;
    }

    private static CheckBomMojo mojo(MavenProject... reactor) {
        CheckBomMojo mojo = new CheckBomMojo();
        mojo.reactorProjects = List.of(reactor);
        return mojo;
    }

    @Test
    void passesWhenEveryPublishableArtifactIsManaged() {
        assertThatCode(mojo(
                bom("platform-build-tools"),
                project("dc-platform", "pom"),
                project("platform-build-tools", "jar"),
                project("platform-build-maven-plugin", "maven-plugin"),
                project("platform-service-archetype", "maven-archetype"),
                project("example-rest-service", "jar"))::execute)
                .doesNotThrowAnyException();
    }

    @Test
    void failsNamingTheMissingArtifactsAndTheFix() {
        assertThatThrownBy(mojo(
                bom("platform-build-tools"),
                project("platform-build-tools", "jar"),
                project("platform-messaging-api", "jar"))::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("platform-messaging-api")
                .hasMessageContaining("Fix:");
    }

    @Test
    void failsWhenBomIsNotInTheReactor() {
        assertThatThrownBy(mojo(project("platform-build-tools", "jar"))::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("repo root");
    }
}
