package ae.gov.dubaicustoms.example.eventdriven.producer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The producer context boots with the in-memory transport (default local profile). */
@SpringBootTest
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
        // Boot is the assertion.
    }
}
