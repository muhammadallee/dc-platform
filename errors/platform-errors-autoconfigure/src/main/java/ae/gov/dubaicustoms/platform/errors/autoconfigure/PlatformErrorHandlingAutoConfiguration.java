package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.errors.ProblemDetailCustomizer;
import ae.gov.dubaicustoms.platform.errors.autoconfigure.internal.ProblemDetailFactory;
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

/*
 * Activates when: servlet web application AND platform-errors-api + spring-webmvc on the
 *                 classpath AND dc.platform.errors.enabled != false
 * Backs off when: user defines a PlatformExceptionHandler bean or a bean named
 *                 platformExceptionHandler (main advice); a PlatformConstraintViolationHandler bean or
 *                 a bean named platformValidationExceptionHandler (constraint-violation advice); any
 *                 ErrorController bean (servlet error page); a bean named errorsCapabilityDescriptor
 * Beans: platformExceptionHandler — maps PlatformException/fallback/request-body validation to
 *                 RFC-9457 ProblemDetail with code/correlationId/timestamp extensions;
 *        platformValidationExceptionHandler — maps jakarta ConstraintViolationException the same
 *                 way (only when jakarta.validation present AND map-validation != false);
 *        platformErrorController — the servlet error dispatch (sendError, exceptions escaping the
 *                 filter chain) as an RFC-9457 body, replacing Boot's BasicErrorController;
 *        errorsCapabilityDescriptor — one line in the startup capability banner
 * Order: the constraint advice is @Order(0), the main advice @Order(LOWEST_PRECEDENCE) — Spring
 *        picks the first advice with any matching handler, so the catch-all must always be last
 *        (application advice beans keep winning for their own exceptions). Runs before Boot's
 *        ErrorMvcAutoConfiguration so its BasicErrorController backs off.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ProblemDetailCustomizer.class, DispatcherServlet.class})
@ConditionalOnProperty(prefix = "dc.platform.errors", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ErrorsProperties.class)
public class PlatformErrorHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(value = PlatformExceptionHandler.class, name = "platformExceptionHandler")
    PlatformExceptionHandler platformExceptionHandler(ErrorsProperties properties,
            ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock) {
        return new PlatformExceptionHandler(properties, factory(properties, customizers, clock));
    }

    // CapabilityDescriptor is a shared type (one per capability), so the override is by name.
    @Bean
    @ConditionalOnMissingBean(name = "errorsCapabilityDescriptor")
    CapabilityDescriptor errorsCapabilityDescriptor() {
        return new CapabilityDescriptor("errors", "ACTIVE", "rfc9457");
    }

    private static ProblemDetailFactory factory(ErrorsProperties properties,
            ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock) {
        // Applications rarely define a Clock bean; fall back to UTC (tests inject fixed clocks).
        return new ProblemDetailFactory(properties, customizers.orderedStream().toList(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.webmvc.error.ErrorController")
    static class ServletErrorPageConfiguration {

        @Bean
        @ConditionalOnMissingBean(type = "org.springframework.boot.webmvc.error.ErrorController")
        PlatformErrorController platformErrorController(ErrorsProperties properties,
                ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock,
                ObjectProvider<org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter> adapter) {
            return new PlatformErrorController(factory(properties, customizers, clock),
                    () -> adapter.getObject().getMessageConverters());
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
                ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock) {
            return new PlatformConstraintViolationHandler(factory(properties, customizers, clock));
        }
    }
}
