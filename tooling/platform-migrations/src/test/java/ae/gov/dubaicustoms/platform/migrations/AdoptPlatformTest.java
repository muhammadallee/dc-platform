package ae.gov.dubaicustoms.platform.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;

/**
 * Validates the declarative recipes load and compose the expected steps. The dependency swaps run only
 * on POMs; they are exercised end-to-end by the rewrite-maven-plugin against a real service. (A
 * {@code pomXml} unit fixture can't run here: rewrite-test reads the machine's Maven settings.xml, and
 * OpenRewrite 8.66 cannot deserialize a {@code <server><httpHeaders>} entry present in this
 * environment — unrelated to the recipe.)
 */
class AdoptPlatformTest {

    private static Recipe activate(String name) {
        Environment env = Environment.builder()
                .load(new YamlResourceLoader(
                        AdoptPlatformTest.class.getResourceAsStream("/META-INF/rewrite/adopt-platform.yml"),
                        URI.create("adopt-platform.yml"), new Properties()))
                .build();
        return env.activateRecipes(name);
    }

    @Test
    void adoptPlatformComposesTheDepSwapsAndTheAdviceFlag() {
        Recipe recipe = activate("ae.gov.dubaicustoms.platform.AdoptPlatform");
        assertThat(recipe.getDisplayName()).isEqualTo("Adopt the DC Platform");

        String steps = recipe.getRecipeList().toString();
        assertThat(recipe.getRecipeList()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(steps)
                .contains("spring-kafka")
                .contains("spring-rabbit")
                .contains("springdoc")
                .contains("FlagHandRolledExceptionHandler");
    }

    @Test
    void upgradeSkeletonActivates() {
        Recipe recipe = activate("ae.gov.dubaicustoms.platform.UpgradeTo_1_0");
        assertThat(recipe.getDisplayName()).isEqualTo("Upgrade a service to DC Platform 1.0");
        assertThat(recipe.getRecipeList()).isNotEmpty();
    }
}
