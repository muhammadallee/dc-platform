package ae.gov.dubaicustoms.platform.build.plugin.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Parses the `properties[].deprecation` entries out of a Spring Boot
// spring-configuration-metadata.json stream. Kept pure (stream in, records out) so the deprecation
// logic is tested from fixture JSON without resolving any jar.
public final class MetadataDeprecations {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataDeprecations() {
    }

    /**
     * Deprecated properties declared in one metadata document. A property is deprecated when it
     * carries a {@code deprecation} object or {@code "deprecated": true}. Malformed or empty
     * documents yield an empty list rather than throwing (callers scan many jars best-effort).
     */
    public static List<DeprecatedProperty> parse(InputStream metadataJson) throws IOException {
        List<DeprecatedProperty> result = new ArrayList<>();
        JsonNode root = MAPPER.readTree(metadataJson);
        JsonNode properties = root.path("properties");
        if (!properties.isArray()) {
            return result;
        }
        for (JsonNode property : properties) {
            JsonNode deprecation = property.get("deprecation");
            boolean deprecatedFlag = property.path("deprecated").asBoolean(false);
            if (deprecation == null && !deprecatedFlag) {
                continue;
            }
            String name = property.path("name").asText(null);
            if (name == null) {
                continue;
            }
            String replacement = text(deprecation, "replacement");
            String reason = text(deprecation, "reason");
            String level = text(deprecation, "level");
            result.add(new DeprecatedProperty(name, replacement, reason, level));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
