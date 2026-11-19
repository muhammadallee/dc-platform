package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal.DefaultPlatformRestClientFactory;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/*
 * Activates when: RestClient (spring-web) on the classpath AND dc.platform.restclient.enabled != false
 * Backs off when: user defines a PlatformRestClientFactory bean; the metrics and health beans below then have no
 *                 breakers to read, and report none.
 * Beans: platformRestClientFactory — DefaultPlatformRestClientFactory: one JDK HttpClient per client name, per-client
 *                 timeouts, deadline, retry of idempotent methods, circuit breaker and bulkhead, traceparent
 *                 propagation (observation), every failure mapped to RemoteCallException, http.client.requests
 *                 observations (client.name = platform client name) on the ObservationRegistry bean
 *                 when one exists (resolved lazily per builder; NOOP otherwise), ordered
 *                 PlatformRestClientCustomizers applied;
 *        restclientCapabilityDescriptor — one line in the startup capability banner;
 *        restclientResilienceMetrics (nested) — Resilience4j's breaker and bulkhead meters and
 *                 platform.restclient.attempts, when Micrometer is present;
 *        restclientHealthIndicator (nested) — reports each breaker's state. Always UP: a remote being down is not
 *                 this pod being unwell. Sits in the dependencies group, never readiness (HD6);
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = "dc.platform.restclient", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RestClientProperties.class)
public class PlatformRestClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PlatformRestClientFactory platformRestClientFactory(RestClientProperties properties,
            ObjectProvider<PlatformRestClientCustomizer> customizers, ObjectProvider<ObservationRegistry> observationRegistry) {
        return new DefaultPlatformRestClientFactory(properties, customizers.orderedStream().toList(),
                () -> observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    CapabilityDescriptor restclientCapabilityDescriptor() {
        return new CapabilityDescriptor("restclient", "ACTIVE", "");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MeterRegistry.class, TaggedCircuitBreakerMetrics.class})
    static class MetricsConfiguration {

        @Bean
        MeterBinder restclientResilienceMetrics(ObjectProvider<PlatformRestClientFactory> factories) {
            // Once per registry: binding one twice would count every event twice. Boot may hand over several user composites.
            Set<MeterRegistry> bound = Collections.newSetFromMap(new IdentityHashMap<>());
            return registry -> {
                if (!(factories.getIfAvailable() instanceof DefaultPlatformRestClientFactory factory)
                        || !bound.add(registry)) {
                    return;
                }
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(factory.circuitBreakers()).bindTo(registry);
                TaggedBulkheadMetrics.ofBulkheadRegistry(factory.bulkheads()).bindTo(registry);
                factory.onAttempt((client, outcome) ->
                        registry.counter("platform.restclient.attempts", "client", client, "outcome", outcome).increment());
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class HealthConfiguration {

        @Bean
        HealthIndicator restclientHealthIndicator(ObjectProvider<PlatformRestClientFactory> factories) {
            return () -> {
                Health.Builder health = Health.up();
                if (!(factories.getIfAvailable() instanceof DefaultPlatformRestClientFactory factory)) {
                    return health.build();
                }
                for (CircuitBreaker breaker : factory.circuitBreakers().getAllCircuitBreakers()) {
                    health.withDetail(breaker.getName(), breaker.getState().name());
                }
                for (Bulkhead bulkhead : factory.bulkheads().getAllBulkheads()) {
                    health.withDetail(bulkhead.getName() + ".available", bulkhead.getMetrics().getAvailableConcurrentCalls());
                }
                return health.build();
            };
        }
    }
}
