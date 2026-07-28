package ae.gov.dubaicustoms.platform.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Generates {@code platform-index.json} and holds it honest against the reactor: it must carry a
 * version, the capabilities with starter coordinates, config keys, and error codes, and every
 * {@code platform-starter-*} in the reactor must appear as a starter of some capability (the
 * completeness gate, mirroring {@link DocsCompletenessTest} for the index).
 */
class PlatformIndexTest {

    private static JsonNode index;

    @BeforeAll
    static void generate() throws IOException {
        Path out = PlatformIndexGenerator.generate();
        assertThat(out).exists();
        index = new ObjectMapper().readTree(out.toFile());
    }

    @Test
    void carriesVersionCapabilitiesPropertiesAndErrorCodes() {
        assertThat(index.get("version").asText()).isNotBlank();

        JsonNode messaging = capability("messaging");
        assertThat(messaging).as("messaging capability present").isNotNull();
        assertThat(messaging.get("starters")).isNotEmpty();
        assertThat(messaging.get("starters").get(0).asText()).contains("platform-starter-messaging");

        assertThat(index.get("properties")).isNotEmpty();
        assertThat(names(index.get("properties"))).anyMatch(n -> n.startsWith("dc.platform."));

        assertThat(codes()).contains("DC-CORE-0500");
    }

    @Test
    void everyStarterAppearsInTheIndex() {
        List<String> indexed = new ArrayList<>();
        index.get("capabilities").forEach(cap -> cap.get("starters").forEach(s -> indexed.add(s.asText())));

        assertThat(PlatformDocs.starterModules()).allSatisfy(starter ->
                assertThat(indexed)
                        .as("starter %s is not represented in platform-index.json capabilities", starter)
                        .anyMatch(coord -> coord.endsWith(":" + starter)));
    }

    private static JsonNode capability(String name) {
        for (JsonNode cap : index.get("capabilities")) {
            if (name.equals(cap.get("name").asText())) {
                return cap;
            }
        }
        return null;
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    private static List<String> codes() {
        List<String> out = new ArrayList<>();
        index.get("errorCodes").forEach(n -> out.add(n.get("code").asText()));
        return out;
    }
}
