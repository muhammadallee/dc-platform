package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase-01 R4.1 / AC15: every removed property stays in the published configuration metadata as
 * deprecated with level {@code error} and a migration reason, so IDEs flag it and Boot's properties
 * migrator reports it. There is no successor property, so no {@code replacement} (scope amendment A1).
 */
class DeprecatedPropertiesMetadataTest {

    private static final List<String> REMOVED = List.of("dc.platform.restclient.propagate-correlation");

    @Test
    @SuppressWarnings("unchecked")
    void removedPropertiesAreDeprecatedAtErrorLevelWithAReason() throws Exception {
        Map<String, Object> metadata;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("generated configuration metadata on the classpath").isNotNull();
            metadata = JsonPath.parse(in).json();
        }
        List<Map<String, Object>> properties = (List<Map<String, Object>>) metadata.get("properties");
        for (String name : REMOVED) {
            assertThat(properties).as(name).filteredOn(p -> name.equals(p.get("name"))).singleElement()
                    .satisfies(property -> {
                        Map<String, Object> deprecation = (Map<String, Object>) property.get("deprecation");
                        assertThat(deprecation).containsEntry("level", "error");
                        assertThat((String) deprecation.get("reason")).contains("traceparent");
                    });
        }
    }
}
