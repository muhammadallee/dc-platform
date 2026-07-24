package ae.gov.dubaicustoms.platform.build.plugin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class PropertyKeyCollectorTest {

    @Test
    void extractsDottedLeafKeysFromYaml(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("application.yml"), """
                dc:
                  platform:
                    messaging:
                      enabled: true   # a comment
                    core:
                      name: demo
                server:
                  port: 8080
                # top-level comment
                ---
                list:
                  - ignored
                """);

        Set<String> keys = PropertyKeyCollector.collect(dir);

        assertThat(keys).contains(
                "dc.platform.messaging.enabled", "dc.platform.core.name", "server.port");
        assertThat(keys).noneMatch(k -> k.contains("ignored"));
    }

    @Test
    void extractsKeysFromPropertiesAcrossProfiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("application.properties"), """
                # comment
                dc.platform.security.enabled=true
                server.port = 9090
                """);
        Files.writeString(dir.resolve("application-prod.yaml"), """
                dc:
                  platform:
                    flags:
                      enabled: false
                """);

        Set<String> keys = PropertyKeyCollector.collect(dir);

        assertThat(keys).contains(
                "dc.platform.security.enabled", "server.port", "dc.platform.flags.enabled");
    }

    @Test
    void returnsEmptyForMissingDirectory(@TempDir Path dir) throws IOException {
        assertThat(PropertyKeyCollector.collect(dir.resolve("does-not-exist"))).isEmpty();
    }
}
