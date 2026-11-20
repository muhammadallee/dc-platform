package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import ae.gov.dubaicustoms.platform.core.autoconfigure.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.UserLogbackConfigurationWarning;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: Logback on the classpath AND dc.platform.logging.enabled != false
 * Backs off when: user defines a bean named loggingCapabilityDescriptor (one-of-many type, D97);
 *                 user defines a UserLogbackConfigurationWarning bean (by type)
 * Beans: loggingCapabilityDescriptor — one line in the startup capability banner reporting the
 *        effective format (the heavy lifting happened pre-context: PlatformLoggingEnvironmentPostProcessor
 *        and the PlatformLogMasking customizer, both from spring.factories);
 *        userLogbackConfigurationWarning — WARNs once at startup when the service's own Logback file replaces
 *        Boot's structured logging in JSON mode, so the platform's masking does not apply to it (D141);
 *        only when dc.platform.logging.redaction.enabled != false
 * Order: none — logging was configured before the context existed.
 */
@AutoConfiguration
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
@ConditionalOnProperty(prefix = "dc.platform.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LoggingProperties.class)
public class LoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "loggingCapabilityDescriptor")
    CapabilityDescriptor loggingCapabilityDescriptor(Environment environment) {
        // The EFFECTIVE format, not the bound record: the local-profile fallback happens in the
        // EnvironmentPostProcessor, before properties exist.
        boolean json = PlatformLoggingEnvironmentPostProcessor.jsonFormatResolved(environment);
        return new CapabilityDescriptor("logging", "ACTIVE", json ? "json" : "console");
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dc.platform.logging.redaction", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    UserLogbackConfigurationWarning userLogbackConfigurationWarning(Environment environment) {
        return new UserLogbackConfigurationWarning(environment,
                PlatformLoggingEnvironmentPostProcessor.jsonFormatResolved(environment));
    }
}
