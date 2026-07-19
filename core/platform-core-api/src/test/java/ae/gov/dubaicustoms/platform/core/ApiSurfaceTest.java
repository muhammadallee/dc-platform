package ae.gov.dubaicustoms.platform.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The phase-03 cap made executable: platform-core-api may expose AT MOST 25 public types, forever.
 * Only concepts needed by three or more capabilities belong in core; everything else lives in its
 * capability's own api module.
 */
class ApiSurfaceTest {

    private static final int MAX_PUBLIC_TYPES = 25;

    @Test
    void publicTypeCountStaysWithinTheCap() {
        // Scan ONLY this module's production output; the test classpath also contains
        // build-tools and this module's own test classes, which are not API surface.
        try (ScanResult scan = new ClassGraph()
                .overrideClasspath("target/classes")
                .enableClassInfo()
                .ignoreClassVisibility() // load all, filter visibility ourselves below
                .scan()) {
            List<String> publicTypes = scan.getAllClasses().stream()
                    .filter(ClassInfo::isPublic)
                    .map(ClassInfo::getName)
                    .sorted()
                    .toList();

            assertThat(publicTypes)
                    .as("public types in platform-core-api (cap %d, add here only what 3+ capabilities need): %s",
                            MAX_PUBLIC_TYPES, publicTypes)
                    .hasSizeLessThanOrEqualTo(MAX_PUBLIC_TYPES);
        }
    }
}
