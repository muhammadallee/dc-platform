package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.resilience.RetryableOperation;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/** Runs the real Resilience4j auto-configuration under the platform's defaults. */
class RestClientRetryInteractionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> new PlatformResilienceEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null))
            .withConfiguration(AutoConfigurations.of(RetryAutoConfiguration.class, PlatformResilienceAutoConfiguration.class));

    @Test
    void theDefaultPolicyDoesNotRepeatAPlatformClientFailure() {
        runner.run(context -> {
            RetryableOperation retry = context.getBean(RetryableOperation.class);
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> retry.call("orders", () -> {
                calls.incrementAndGet();
                throw new RemoteCallException("orders", 503, "", null, null);
            })).isInstanceOf(RemoteCallException.class);

            assertThat(calls).hasValue(1);
        });
    }

    @Test
    void theDefaultPolicyDoesNotRepeatARawClientFailure() {
        runner.run(context -> {
            RetryableOperation retry = context.getBean(RetryableOperation.class);
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> retry.call("orders", () -> {
                calls.incrementAndGet();
                throw new ResourceAccessException("down");
            })).isInstanceOf(ResourceAccessException.class);

            assertThat(calls).hasValue(1);
        });
    }

    @Test
    void theDefaultPolicyDoesNotRepeatAWrappedBodyFailure() {
        runner.run(context -> {
            RetryableOperation retry = context.getBean(RetryableOperation.class);
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> retry.call("orders", () -> {
                calls.incrementAndGet();
                throw new RestClientException("Error while extracting response");
            })).isInstanceOf(RestClientException.class);

            assertThat(calls).hasValue(1);
        });
    }

    @Test
    void aMetricsSwitchAloneIsNotAThreadPoolBulkhead() {
        runner.withPropertyValues("spring.threads.virtual.enabled=true", "resilience4j.thread-pool-bulkhead.metrics.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theDefaultPolicyStillRepeatsOtherFailures() {
        runner.run(context -> {
            RetryableOperation retry = context.getBean(RetryableOperation.class);
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> retry.call("orders", () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("flaky");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(calls).hasValue(3);
        });
    }

    @Test
    void aThreadPoolBulkheadWithVirtualThreadsFailsStartupWithAReason() {
        runner.withPropertyValues("spring.threads.virtual.enabled=true", "resilience4j.thread-pool-bulkhead.instances.a.core-thread-pool-size=2")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("resilience4j.thread-pool-bulkhead").hasMessageContaining("spring.threads.virtual.enabled"));
    }

    @Test
    void aThreadPoolBulkheadWithoutVirtualThreadsIsAllowed() {
        runner.withPropertyValues("resilience4j.thread-pool-bulkhead.instances.a.core-thread-pool-size=2")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void virtualThreadsWithoutAThreadPoolBulkheadAreAllowed() {
        runner.withPropertyValues("spring.threads.virtual.enabled=true").run(context -> assertThat(context).hasNotFailed());
    }
}
