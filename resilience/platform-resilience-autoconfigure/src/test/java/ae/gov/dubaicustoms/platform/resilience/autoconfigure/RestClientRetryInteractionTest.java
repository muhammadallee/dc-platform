package ae.gov.dubaicustoms.platform.resilience.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.resilience.RetryableOperation;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal.DefaultPlatformRestClientFactory;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
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
    void aRealPostThroughARealClientIsSentOnceInsideTheDefaultPolicy() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger received = new AtomicInteger();
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var factory = new DefaultPlatformRestClientFactory(new RestClientProperties(true, null, null), List.of());
        try {
            var client = factory.builder("orders").baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            runner.run(context -> {
                RetryableOperation retry = context.getBean(RetryableOperation.class);

                assertThatThrownBy(() -> retry.call("orders", () -> client.post().uri("/a").body("x").retrieve().toBodilessEntity()))
                        .isInstanceOf(RemoteCallException.class);

                assertThat(received).hasValue(1);
            });
        } finally {
            factory.destroy();
            server.stop(0);
        }
    }

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
    void theBackendsAliasIsAThreadPoolBulkheadToo() {
        runner.withPropertyValues("spring.threads.virtual.enabled=true", "resilience4j.thread-pool-bulkhead.backends.a.core-thread-pool-size=2")
                .run(context -> assertThat(context).hasFailed());
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
