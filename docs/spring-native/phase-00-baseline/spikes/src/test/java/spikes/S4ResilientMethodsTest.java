package spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;

/**
 * S4: Spring Framework 7's {@code @Retryable}. Boot 4.1.2 has no auto-configuration for it; a platform
 * auto-configuration can switch it on with {@code @EnableResilientMethods}, and the service then uses the
 * Spring annotation only. Expectation: 1 call + 2 retries on an included exception; no retry on an excluded one.
 */
@SpringBootTest(classes = S4ResilientMethodsTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S4ResilientMethodsTest {

    /** Stands in for a platform auto-configuration class. */
    @Configuration(proxyBeanMethods = false)
    @EnableResilientMethods
    static class PlatformResilience {
    }

    static class Remote {
        /** Static: the bean is a CGLIB proxy, whose own instance fields are not the target's. */
        static final AtomicInteger TRANSIENT_CALLS = new AtomicInteger();
        static final AtomicInteger PERMANENT_CALLS = new AtomicInteger();

        @Retryable(maxRetries = 2, delay = 0, includes = IllegalStateException.class)
        public String flaky() {
            TRANSIENT_CALLS.incrementAndGet();
            throw new IllegalStateException("transient");
        }

        @Retryable(maxRetries = 2, delay = 0, excludes = IllegalArgumentException.class)
        public String rejected() {
            PERMANENT_CALLS.incrementAndGet();
            throw new IllegalArgumentException("4xx-like");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PlatformResilience.class)
    static class App {
        @Bean
        Remote remote() {
            return new Remote();
        }
    }

    @Autowired
    Remote remote;

    @Test
    void retriesIncludedException() {
        assertThatThrownBy(remote::flaky).isInstanceOf(IllegalStateException.class);
        assertThat(Remote.TRANSIENT_CALLS).hasValue(3);
    }

    @Test
    void doesNotRetryExcludedException() {
        assertThatThrownBy(remote::rejected).isInstanceOf(IllegalArgumentException.class);
        assertThat(Remote.PERMANENT_CALLS).hasValue(1);
    }
}
