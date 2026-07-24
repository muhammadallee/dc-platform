package ae.gov.dubaicustoms.platform.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The phase-14 completeness gate. Two invariants keep the docs honest against the code:
 * <ol>
 *   <li>every {@code dc.platform.*} metadata key appears in the generated properties reference;</li>
 *   <li>every {@code platform-starter-*} in the reactor is documented by a capability page.</li>
 * </ol>
 * Regenerates the properties reference first so the assertion never races the generator test.
 */
class DocsCompletenessTest {

    @BeforeAll
    static void regenerate() {
        PropertiesReferenceGenerator.generate();
    }

    @Test
    void everyMetadataKeyIsDocumented() throws IOException {
        List<PropertiesReferenceGenerator.Prop> props = PropertiesReferenceGenerator.scan();
        assertThat(props)
                .as("no dc.platform.* metadata found — the reactor was not built before the docs module")
                .isNotEmpty();

        String reference = Files.readString(PlatformDocs.docsRoot().resolve("reference/properties.md"));
        assertThat(props)
                .allSatisfy(p -> assertThat(reference)
                        .as("property %s is missing from reference/properties.md", p.name())
                        .contains(p.name()));
    }

    @Test
    void everyStarterHasACapabilityPage() {
        List<String> starters = PlatformDocs.starterModules();
        assertThat(starters)
                .as("no platform-starter-* modules discovered in the root POM")
                .isNotEmpty();

        assertThat(starters).allSatisfy(starter -> {
            Path page = PlatformDocs.pageForStarter(starter);
            assertThat(page)
                    .as("starter %s has no capability page mapping in PlatformDocs.STARTER_TO_PAGE", starter)
                    .isNotNull();
            assertThat(page)
                    .as("capability page for starter %s does not exist", starter)
                    .exists();
        });
    }
}
