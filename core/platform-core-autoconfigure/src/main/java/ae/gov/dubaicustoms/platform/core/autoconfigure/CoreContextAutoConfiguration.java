package ae.gov.dubaicustoms.platform.core.autoconfigure;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingFilterBean;
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
 *                 (registration additionally: micrometer-tracing on the classpath)
 * Backs off when: user defines a CorrelationIdFilter bean or a FilterRegistrationBean<CorrelationIdFilter>
 *                 (by type, Boot's @ConditionalOnMissingFilterBean — the same shape as Boot's own
 *                 webMvcObservationFilter). A consumer's CorrelationIdFilter bean is then registered
 *                 by Boot itself: it is Ordered at FILTER_ORDER and, as a OncePerRequestFilter, runs
 *                 on every dispatch type, so it keeps the platform's position.
 * Beans: platformCorrelationFilterRegistration — registers a CorrelationIdFilter (created inline,
 *                              not a separate bean) for all URLs at FILTER_ORDER, on REQUEST, ASYNC
 *                              and ERROR dispatches. The filter opens RequestContext with the current
 *                              span's W3C trace ID (MDC traceId/spanId) for the request and always
 *                              closes it; never reads, generates or echoes a correlation header.
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
        @ConditionalOnMissingFilterBean(CorrelationIdFilter.class)
        FilterRegistrationBean<CorrelationIdFilter> platformCorrelationFilterRegistration(ObjectProvider<Tracer> tracer) {
            FilterRegistrationBean<CorrelationIdFilter> registration =
                    new FilterRegistrationBean<>(new CorrelationIdFilter(tracer.getIfAvailable(() -> Tracer.NOOP)));
            registration.setOrder(FILTER_ORDER);
            registration.addUrlPatterns("/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            return registration;
        }
    }
}
