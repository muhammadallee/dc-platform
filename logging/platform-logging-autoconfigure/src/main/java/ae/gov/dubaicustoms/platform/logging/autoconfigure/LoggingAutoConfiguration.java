package ae.gov.dubaicustoms.platform.logging.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.logging.Kv;
import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: platform-logging-api on the classpath AND dc.platform.logging.enabled != false
 * Backs off when: user defines a bean named loggingCapabilityDescriptor (one-of-many type, D97);
 *                 user defines a LogSanitizerRegistry bean (by type, D98)
 * Beans: loggingCapabilityDescriptor — one line in the startup capability banner reporting the
 *        effective format (the heavy lifting happened pre-context in
 *        PlatformLoggingEnvironmentPostProcessor);
 *        logSanitizerRegistry — hands the application's LogSanitizer beans to the platform JSON
 *        encoder once every singleton exists (Logback starts before the context, so the encoder
 *        cannot look them up itself); only when dc.platform.logging.redaction.enabled != false
 * Order: none — the EPP already ran before the context existed; the registry publishes after all
 *        singletons exist, so every LogSanitizer bean is seen regardless of configuration order.
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

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "dc.platform.logging.redaction", name = "enabled",
                           havingValue = "true", matchIfMissing = true)
    LogSanitizerRegistry logSanitizerRegistry(ObjectProvider<LogSanitizer> sanitizers) {
        return new LogSanitizerRegistry(sanitizers);
    }
}
