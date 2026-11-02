package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.autoconfigure.PlatformSecurityAutoConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.client.RestClient;

/** The mandatory 5-case ContextRunner matrix for PlatformRestClientAutoConfiguration, plus the token-relay guard. */
class PlatformRestClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformRestClientAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PlatformRestClientFactory.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.restclient.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformRestClientFactory.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        PlatformRestClientFactory mine = name -> RestClient.builder();
        runner.withBean("mine", PlatformRestClientFactory.class, () -> mine)
                .run(context -> assertThat(context.getBean(PlatformRestClientFactory.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(RestClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformRestClientAutoConfiguration.class));
    }

    @Test
    void bindsDefaultsAndPerClientResilience() {
        runner.withPropertyValues("dc.platform.restclient.defaults.deadline=7s",
                "dc.platform.restclient.defaults.retry.max-attempts=2",
                "dc.platform.restclient.clients.orders.retry.methods=GET,head",
                "dc.platform.restclient.clients.orders.circuit-breaker.enabled=false",
                "dc.platform.restclient.clients.orders.bulkhead.max-concurrent=4")
                .run(context -> {
                    var settings = context.getBean(RestClientProperties.class).timeoutsFor("orders");
                    assertThat(settings.deadline()).hasSeconds(7);
                    assertThat(settings.retry().maxAttempts()).isEqualTo(2);
                    assertThat(settings.retry().methods()).containsExactlyInAnyOrder("GET", "HEAD");
                    assertThat(settings.circuitBreaker().enabled()).isFalse();
                    assertThat(settings.bulkhead().maxConcurrent()).isEqualTo(4);
                });
    }

    @Test
    void theDefaultsAreWhatTheDocumentationSays() {
        runner.run(context -> {
            var settings = context.getBean(RestClientProperties.class).defaults();
            assertThat(settings.connectTimeout()).hasSeconds(2);
            assertThat(settings.readTimeout()).hasSeconds(10);
            assertThat(settings.deadline()).hasSeconds(30);
            assertThat(settings.retry()).isEqualTo(new RestClientProperties.Retry(true, 3, java.time.Duration.ofMillis(200), 2.0,
                    java.time.Duration.ofSeconds(5), java.util.Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE")));
            assertThat(settings.circuitBreaker()).isEqualTo(new RestClientProperties.CircuitBreaker(true, 50f, 10, 10,
                    java.time.Duration.ofSeconds(30), 3));
            assertThat(settings.bulkhead()).isEqualTo(new RestClientProperties.Bulkhead(true, RestClientProperties.BulkheadType.SEMAPHORE, 25));
        });
    }

    @Test
    void configuringPostAsRetryableFailsStartupWithAReason() {
        runner.withPropertyValues("dc.platform.restclient.clients.orders.retry.methods=GET,POST")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("retry.methods").hasMessageContaining("POST"));
    }

    @Test
    void aThreadPoolBulkheadFailsStartupWithAReason() {
        runner.withPropertyValues("dc.platform.restclient.defaults.bulkhead.type=THREAD_POOL")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("THREAD_POOL"));
    }

    @Test
    void healthIsUpWithTheBreakerStateAndMetricsAreBound() {
        runner.run(context -> {
            var factory = context.getBean(PlatformRestClientFactory.class);
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            context.getBean(io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(registry);
            var failing = factory.builder("orders").baseUrl("http://127.0.0.1:1").build();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.get().uri("/a").retrieve().toBodilessEntity())
                    .isInstanceOf(ae.gov.dubaicustoms.platform.restclient.RemoteCallException.class);

            var health = context.getBean(org.springframework.boot.health.contributor.HealthIndicator.class).health();

            assertThat(health.getStatus().getCode()).isEqualTo("UP");
            assertThat(health.getDetails()).containsEntry("rest-orders", "CLOSED");
            assertThat(registry.get("platform.restclient.attempts").tag("client", "orders").tag("outcome", "connection").counter().count())
                    .isEqualTo(3);
            assertThat(registry.find("resilience4j.circuitbreaker.state").tag("name", "rest-orders").gauges()).isNotEmpty();
            assertThat(registry.find("resilience4j.bulkhead.available.concurrent.calls").tag("name", "rest-orders").gauges()).isNotEmpty();
        });
    }

    @Test
    void metricsBindOncePerRegistry() {
        runner.run(context -> {
            var factory = context.getBean(PlatformRestClientFactory.class);
            var first = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            var second = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            var binder = context.getBean(io.micrometer.core.instrument.binder.MeterBinder.class);
            binder.bindTo(first);
            binder.bindTo(first);
            binder.bindTo(second);
            var failing = factory.builder("orders").baseUrl("http://127.0.0.1:1").build();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.get().uri("/a").retrieve().toBodilessEntity())
                    .isInstanceOf(ae.gov.dubaicustoms.platform.restclient.RemoteCallException.class);

            assertThat(first.get("platform.restclient.attempts").counter().count()).isEqualTo(3);
            assertThat(second.get("platform.restclient.attempts").counter().count()).isEqualTo(3);
        });
    }

    @Test
    void aReplacedFactoryLeavesHealthAndMetricsEmpty() {
        PlatformRestClientFactory mine = name -> RestClient.builder();
        runner.withBean("mine", PlatformRestClientFactory.class, () -> mine).run(context -> {
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            context.getBean(io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(registry);

            assertThat(registry.getMeters()).isEmpty();
            assertThat(context.getBean(org.springframework.boot.health.contributor.HealthIndicator.class).health().getDetails()).isEmpty();
        });
    }

    @Test
    void removedPropagateCorrelationKeyHasNoEffect() {
        // AC15: the removed key is accepted (no binding failure) and changes nothing.
        runner.withPropertyValues("dc.platform.restclient.propagate-correlation=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RestClientProperties.class))
                            .isEqualTo(new RestClientProperties(true, null, null));
                });
    }

    @Test
    void propertiesBindFromKebabKeys() {
        runner.withPropertyValues(
                        "dc.platform.restclient.defaults.connect-timeout=5s",
                        "dc.platform.restclient.clients.orders.read-timeout=1s")
                .run(context -> {
                    RestClientProperties properties = context.getBean(RestClientProperties.class);
                    assertThat(properties.defaults().connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
                    assertThat(properties.timeoutsFor("orders").readTimeout()).isEqualTo(java.time.Duration.ofSeconds(1));
                });
    }

    @Test
    void invalidTimeoutFailsStartupInsteadOfFallingBackToADefault() {
        runner.withPropertyValues("dc.platform.restclient.clients.orders.read-timeout=soon")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("soon"));
    }

    @Test
    void tokenRelayCustomizerBacksOffWithoutCurrentUserAccessor() {
        runner.run(context -> assertThat(context).doesNotHaveBean("platformTokenRelayCustomizer"));
    }

    @Test
    void tokenRelayCustomizerRegistersWhenCurrentUserAccessorPresent() {
        CurrentUserAccessor accessor = () -> Optional.<CurrentUser>empty();
        runner.withBean(CurrentUserAccessor.class, () -> accessor)
                .run(context -> assertThat(context).hasBean("platformTokenRelayCustomizer"));
    }

    @Test
    void tokenRelayRegistersUnderTheRealAutoConfigurationOrderWithTheSecurityCapability() {
        // Both auto-configurations sorted exactly as Boot sorts them in an application. A user-supplied
        // accessor (above) is registered before any auto-configuration and hides ordering bugs; here the
        // accessor comes from PlatformSecurityAutoConfiguration itself. The user chain only stands in for
        // HttpSecurity, which this runner does not provide; the accessor does not depend on it.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformRestClientAutoConfiguration.class, PlatformSecurityAutoConfiguration.class))
                .withBean(SecurityFilterChain.class, () -> new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE))
                .run(context -> {
                    assertThat(context).hasSingleBean(CurrentUserAccessor.class);
                    assertThat(context).hasBean("platformTokenRelayCustomizer");
                });
    }
}
