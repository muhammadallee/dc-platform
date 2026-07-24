package ae.gov.dubaicustoms.example.minimal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The context boots with only the floor starters on the classpath. If the platform's core, errors,
 * and logging auto-configuration cannot co-exist in a bare service, this fails first.
 */
@SpringBootTest
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
        // Boot is the assertion: a failure to wire any platform bean fails this test.
    }
}
