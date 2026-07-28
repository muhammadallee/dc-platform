package ae.gov.dubaicustoms.platform.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Generates the skill bundle into {@code target/generated-skill} (which the assembly zips) and holds it
 * honest: SKILL.md must carry the train version (the staleness gate), trigger phrases, and a capability.
 */
class SkillGeneratorTest {

    @Test
    void generatesSkillBundleStampedWithTheTrainVersion() throws IOException {
        Path index = SkillGenerator.indexJson();
        // Written to the committed skill/ dir (the assembly zips it) so the zip has content even
        // under -DskipTests; regenerated + version-checked here on every test run. Resolved from the
        // reactor root so it lands in the module regardless of the test working directory.
        Path outDir = SkillGenerator.skillDir();
        Path skill = SkillGenerator.generate(index, outDir);

        assertThat(skill).exists();
        String md = Files.readString(skill);
        String version = new ObjectMapper().readTree(index.toFile()).path("version").asText();

        // Staleness gate: the skill's version must equal the train version in the index.
        assertThat(md).contains("Train version " + version).contains("Version `" + version + "`");
        // Trigger tuning + content.
        assertThat(md).contains("name: dc-platform").contains("DC Platform").contains("messaging");

        assertThat(outDir.resolve("resources/capabilities.md")).exists();
        assertThat(outDir.resolve("resources/property-cheatsheet.md")).exists();
        assertThat(Files.readString(outDir.resolve("resources/property-cheatsheet.md")))
                .contains("dc.platform.");
    }
}
