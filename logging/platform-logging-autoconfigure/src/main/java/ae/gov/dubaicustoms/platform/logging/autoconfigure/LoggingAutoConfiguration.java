package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.logging.Kv;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: platform-logging-api on the classpath AND dc.platform.logging.enabled != false
 * Backs off when: user defines a bean named loggingCapabilityDescriptor
 * Beans: loggingCapabilityDescriptor — one line in the startup capability banner reporting the
 *        effective format (the heavy lifting happened pre-context in
 *        PlatformLoggingEnvironmentPostProcessor; LogSanitizer beans are collected by the
 *        enrichment components of later phases)
 * Order: none — no bean interactions; the EPP already ran before the context existed.
 */
@AutoConfiguration
@ConditionalOnClass(Kv.class)
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
}
