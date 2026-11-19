package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.core.autoconfigure.RequestTrace;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.TraceIdSource;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/*
 * Activates when: servlet web application AND spring-webmvc on the classpath AND dc.platform.errors.enabled != false
 * Backs off when: user defines a PlatformExceptionHandler bean or a bean named platformExceptionHandler (main
 *                 advice); a PlatformConstraintViolationHandler bean or a bean named
 *                 platformValidationExceptionHandler (constraint-violation advice); any ErrorController bean (servlet
 *                 error page); a bean named errorsCapabilityDescriptor
 * Beans: platformExceptionHandler — maps every exception a controller lets through to an RFC 9457 ProblemDetail
 *                 with code/traceId/timestamp/retryable, returned as an ErrorResponseException whose cause is the
 *                 original exception (so a service's ErrorResponse.Interceptor sees both, D138);
 *        platformValidationExceptionHandler — maps jakarta ConstraintViolationException the same way (only when
 *                 jakarta.validation present AND map-validation != false);
 *        platformErrorController — the servlet error dispatch (sendError, exceptions escaping the filter chain) as
 *                 an RFC 9457 body, with the application's ErrorResponse.Interceptors applied, replacing Boot's
 *                 BasicErrorController;
 *        platformErrorsTraceIdSource — the traceId of each body, from Micrometer's Tracer (the request's observation
 *                 on an ERROR dispatch); only with micrometer-tracing on the classpath, otherwise bodies carry none;
 *        errorsCapabilityDescriptor — one line in the startup capability banner
 * Order: the constraint advice is @Order(0), the main advice @Order(LOWEST_PRECEDENCE) — Spring picks the first
 *        advice with any matching handler, so the catch-all must always be last (application advice beans keep
 *        winning for their own exceptions). Runs before Boot's ErrorMvcAutoConfiguration so its BasicErrorController
 *        backs off.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "dc.platform.errors", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ErrorsProperties.class)
public class PlatformErrorHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(value = PlatformExceptionHandler.class, name = "platformExceptionHandler")
    PlatformExceptionHandler platformExceptionHandler(ErrorsProperties properties,
            ObjectProvider<TraceIdSource> traceIds, ObjectProvider<Clock> clock) {
        return new PlatformExceptionHandler(properties, factory(properties, traceIds, clock));
    }

    // CapabilityDescriptor is a shared type (one per capability), so the override is by name.
    @Bean
    @ConditionalOnMissingBean(name = "errorsCapabilityDescriptor")
    CapabilityDescriptor errorsCapabilityDescriptor() {
        return new CapabilityDescriptor("errors", "ACTIVE", "rfc9457");
    }

    private static ProblemDetailFactory factory(ErrorsProperties properties, ObjectProvider<TraceIdSource> traceIds,
            ObjectProvider<Clock> clock) {
        // Applications rarely define a Clock bean; fall back to UTC (tests inject fixed clocks).
        return new ProblemDetailFactory(properties, traceIds.getIfAvailable(() -> TraceIdSource.NONE),
                clock.getIfAvailable(Clock::systemUTC));
    }

    // Nested so the tracing types are touched only when micrometer-tracing is present.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TraceIdConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "platformErrorsTraceIdSource")
        TraceIdSource platformErrorsTraceIdSource(ObjectProvider<Tracer> tracer) {
            return request -> {
                TraceContext trace = RequestTrace.current(tracer.getIfAvailable(() -> Tracer.NOOP), request);
                return trace == null ? null : trace.traceId();
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.webmvc.error.ErrorController")
    static class ServletErrorPageConfiguration {

        @Bean
        @ConditionalOnMissingBean(type = "org.springframework.boot.webmvc.error.ErrorController")
        PlatformErrorController platformErrorController(ErrorsProperties properties,
                ObjectProvider<TraceIdSource> traceIds, ObjectProvider<Clock> clock,
                ObjectProvider<RequestMappingHandlerAdapter> adapter) {
            return new PlatformErrorController(factory(properties, traceIds, clock),
                    () -> adapter.getObject().getMessageConverters(),
                    () -> adapter.getObject().getErrorResponseInterceptors());
        }
    }

    // Nested so the jakarta.validation types in the advice signature are only touched when the
    // class is actually present; a plain @ConditionalOnClass bean method would still let Spring
    // trip over the missing type while introspecting the enclosing configuration.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ConstraintViolationException.class)
    @ConditionalOnProperty(prefix = "dc.platform.errors", name = "map-validation",
            havingValue = "true", matchIfMissing = true)
    static class ConstraintViolationMappingConfiguration {

        @Bean
        @ConditionalOnMissingBean(value = PlatformConstraintViolationHandler.class,
                name = "platformValidationExceptionHandler")
        PlatformConstraintViolationHandler platformValidationExceptionHandler(ErrorsProperties properties,
                ObjectProvider<TraceIdSource> traceIds, ObjectProvider<Clock> clock) {
            return new PlatformConstraintViolationHandler(factory(properties, traceIds, clock));
        }
    }
}
