package ae.gov.dubaicustoms.platform.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Runs the three build-time reference generators (properties, error codes, BOM) so a plain
 * {@code mvn verify} regenerates {@code docs/reference/*.md}, and asserts each produced a non-empty
 * table. The completeness assertions live in {@link DocsCompletenessTest}.
 */
class ReferenceDocsGeneratorTest {

    @Test
    void generatesPropertiesReference() throws IOException {
        Path out = PropertiesReferenceGenerator.generate();
        assertThat(out).exists();
        assertThat(Files.readString(out)).contains("# Configuration properties").contains("dc.platform.");
    }

    @Test
    void generatesErrorCodesReference() throws IOException {
        Path out = ErrorCodesReferenceGenerator.generate();
        assertThat(out).exists();
        assertThat(Files.readString(out)).contains("# Error codes").contains("DC-CORE-0500");
    }

    @Test
    void generatesBomReference() throws IOException {
        Path out = BomReferenceGenerator.generate();
        assertThat(out).exists();
        assertThat(Files.readString(out)).contains("# Platform BOM").contains("platform-bom");
    }
}
