package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The {@code service} member: explicit name, else the application name, else {@code application}. */
class PlatformLogstashFormatterTest {

    @Test
    void serviceNameDefaultsToSpringApplicationName() {
        assertThat(PlatformLogstashFormatter.serviceName(new MockEnvironment()
                .withProperty("spring.application.name", "orders"))).isEqualTo("orders");
    }

    @Test
    void anExplicitServiceNameBeatsTheApplicationName() {
        assertThat(PlatformLogstashFormatter.serviceName(new MockEnvironment()
                .withProperty("spring.application.name", "orders")
                .withProperty("dc.platform.logging.service-name", "orders-api"))).isEqualTo("orders-api");
    }

    @Test
    void serviceNameFallsBackToApplicationWhenUnnamed() {
        assertThat(PlatformLogstashFormatter.serviceName(new MockEnvironment())).isEqualTo("application");
    }
}
