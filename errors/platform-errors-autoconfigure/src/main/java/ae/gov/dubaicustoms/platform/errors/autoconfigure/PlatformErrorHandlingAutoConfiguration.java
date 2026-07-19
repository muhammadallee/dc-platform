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
 * Backs off when: user defines a bean named platformExceptionHandler (main advice) or
 *                 platformValidationExceptionHandler (constraint-violation advice)
 * Beans: platformExceptionHandler — maps PlatformException/fallback/request-body validation to
 *                 RFC-9457 ProblemDetail with code/correlationId/timestamp extensions;
 *        platformValidationExceptionHandler — maps jakarta ConstraintViolationException the same
 *                 way (only when jakarta.validation present AND map-validation != false);
 *        errorsCapabilityDescriptor — one line in the startup capability banner
 * Order: the constraint advice is @Order(0), the main advice @Order(LOWEST_PRECEDENCE) — Spring
 *        picks the first advice with any matching handler, so the catch-all must always be last
 *        (application advice beans keep winning for their own exceptions).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ProblemDetailCustomizer.class, DispatcherServlet.class})
@ConditionalOnProperty(prefix = "dc.platform.errors", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ErrorsProperties.class)
public class PlatformErrorHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "platformExceptionHandler")
    PlatformExceptionHandler platformExceptionHandler(ErrorsProperties properties,
            ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock) {
        return new PlatformExceptionHandler(properties, factory(properties, customizers, clock));
    }

    @Bean
    CapabilityDescriptor errorsCapabilityDescriptor() {
        return new CapabilityDescriptor("errors", "ACTIVE", "rfc9457");
    }

    private static ProblemDetailFactory factory(ErrorsProperties properties,
            ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock) {
        // Applications rarely define a Clock bean; fall back to UTC (tests inject fixed clocks).
        return new ProblemDetailFactory(properties, customizers.orderedStream().toList(),
                clock.getIfAvailable(Clock::systemUTC));
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
        @ConditionalOnMissingBean(name = "platformValidationExceptionHandler")
        PlatformConstraintViolationHandler platformValidationExceptionHandler(ErrorsProperties properties,
                ObjectProvider<ProblemDetailCustomizer> customizers, ObjectProvider<Clock> clock) {
            return new PlatformConstraintViolationHandler(factory(properties, customizers, clock));
        }
    }
}
