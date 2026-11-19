package ae.gov.dubaicustoms.example.eventdriven.producer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The producer context boots with the RabbitMQ transport; no broker is needed until a message is sent. */
@SpringBootTest
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
        // Boot is the assertion.
    }
}
