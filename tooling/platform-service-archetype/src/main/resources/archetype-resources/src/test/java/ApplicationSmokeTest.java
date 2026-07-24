package ${package};

import ae.gov.dubaicustoms.platform.test.junit.PlatformTest;
import org.junit.jupiter.api.Test;

/**
 * Boots the full platform context to prove the service wiring is valid end to end. If a starter or
 * bean is misconfigured, this fails fast at context load.
 */
@PlatformTest
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
        // Context boot is the assertion.
    }
}
