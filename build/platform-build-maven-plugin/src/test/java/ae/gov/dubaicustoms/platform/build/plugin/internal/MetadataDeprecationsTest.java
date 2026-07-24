package ae.gov.dubaicustoms.platform.build.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MetadataDeprecationsTest {

    private static java.io.InputStream json(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesDeprecationObjectWithReplacementReasonAndLevel() throws IOException {
        String metadata = """
                { "properties": [
                  { "name": "dc.platform.old.key", "type": "java.lang.String",
                    "deprecation": { "replacement": "dc.platform.new.key", "reason": "renamed", "level": "error" } }
                ] }""";

        var deprecations = MetadataDeprecations.parse(json(metadata));

        assertThat(deprecations).hasSize(1);
        DeprecatedProperty deprecated = deprecations.get(0);
        assertThat(deprecated.name()).isEqualTo("dc.platform.old.key");
        assertThat(deprecated.replacement()).isEqualTo("dc.platform.new.key");
        assertThat(deprecated.reason()).isEqualTo("renamed");
        assertThat(deprecated.level()).isEqualTo("error");
    }

    @Test
    void treatsDeprecatedBooleanFlagAsDeprecated() throws IOException {
        String metadata = """
                { "properties": [ { "name": "dc.platform.gone", "deprecated": true } ] }""";

        var deprecations = MetadataDeprecations.parse(json(metadata));

        assertThat(deprecations).singleElement()
                .satisfies(d -> assertThat(d.name()).isEqualTo("dc.platform.gone"));
        assertThat(deprecations.get(0).replacement()).isNull();
    }

    @Test
    void ignoresNonDeprecatedPropertiesAndEmptyDocuments() throws IOException {
        String metadata = """
                { "properties": [ { "name": "dc.platform.active", "type": "java.lang.String" } ] }""";

        assertThat(MetadataDeprecations.parse(json(metadata))).isEmpty();
        assertThat(MetadataDeprecations.parse(json("{}"))).isEmpty();
    }
}
