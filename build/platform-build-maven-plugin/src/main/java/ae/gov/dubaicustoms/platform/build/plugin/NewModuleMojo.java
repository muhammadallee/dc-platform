package ae.gov.dubaicustoms.platform.build.plugin;

import ae.gov.dubaicustoms.platform.build.plugin.internal.ModuleGenerator;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Scaffolds a new platform module (POM, packages, ArchConstitutionTest, autoconfigure canon for
 * kind=autoconfigure) and registers it in the root reactor and platform-bom.
 *
 * <p>Usage: {@code mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:new-module
 * -Dcapability=messaging -Dkind=api} (kinds: api|spi|impl|autoconfigure|starter|test;
 * impl/starter accept {@code -Dprovider=kafka}).
 *
 * @since 0.1.0
 */
@Mojo(name = "new-module", aggregator = true)
public class NewModuleMojo extends AbstractMojo {

    // Package-private for tests (mojo parameter injection has no constructor).
    @Parameter(property = "capability", required = true)
    String capability;

    @Parameter(property = "kind", required = true)
    String kind;

    @Parameter(property = "provider")
    String provider;

    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true, required = true)
    File rootDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ModuleGenerator.GeneratedModule generated =
                    new ModuleGenerator(rootDirectory.toPath()).generate(capability, kind, provider);
            getLog().info("Generated " + generated.artifactId() + " at " + generated.modulePath()
                    + " and registered it in the root <modules> and platform-bom.");
            getLog().info("Next: fill the TODOs, then run: mvn -T1C -pl " + generated.modulePath()
                    + " -am verify");
        } catch (IllegalArgumentException | IOException e) {
            throw new MojoExecutionException("new-module failed: " + e.getMessage(), e);
        }
    }
}
