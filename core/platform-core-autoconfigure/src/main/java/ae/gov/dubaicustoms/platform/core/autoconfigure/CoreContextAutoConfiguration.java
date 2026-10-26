package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/*
 * Activates when: servlet web application AND platform-core-api on the classpath
 *                 AND dc.platform.core.enabled != false
 *                 (filter beans additionally: micrometer-tracing on the classpath)
 * Backs off when: user defines a CorrelationIdFilter bean (the registration then wraps theirs),
 *                 or a bean named platformCorrelationFilterRegistration (then the platform creates
 *                 NEITHER bean: a default filter left behind would be auto-registered by Boot)
 * Beans: correlationIdFilter — opens RequestContext with the current span's W3C trace ID (MDC
 *                              traceId/spanId) for the request and always closes it; never reads,
 *                              generates or echoes a correlation header;
 *        platformCorrelationFilterRegistration — registers the filter for all URLs at
 *                              FILTER_ORDER, on REQUEST, ASYNC and ERROR dispatches
 * Order: FILTER_ORDER = HIGHEST_PRECEDENCE + 10: after Boot's ServerHttpObservationFilter
 *        (HIGHEST_PRECEDENCE + 1), which continues or starts the trace from traceparent, and well
 *        before the Spring Security chain (-100), so 401/403 bodies and security log lines already
 *        carry the trace ID.
 * Without a Tracer bean (tracing bridge absent or disabled) the filter passes requests through
 * without a correlation context rather than inventing an id.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(CorrelationId.class)
@ConditionalOnProperty(prefix = "dc.platform.core", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CoreProperties.class)
public class CoreContextAutoConfiguration {

    /** Servlet filter order of the correlation filter; see the activation comment above. */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TraceCorrelationConfiguration {

        @Bean
        @ConditionalOnMissingBean(value = CorrelationIdFilter.class, name = "platformCorrelationFilterRegistration")
        CorrelationIdFilter correlationIdFilter(ObjectProvider<Tracer> tracer) {
            return new CorrelationIdFilter(tracer.getIfAvailable(() -> Tracer.NOOP));
        }

        @Bean
        @ConditionalOnMissingBean(name = "platformCorrelationFilterRegistration")
        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(CorrelationIdFilter filter) {
            FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(FILTER_ORDER);
            registration.addUrlPatterns("/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            return registration;
        }
    }
}
