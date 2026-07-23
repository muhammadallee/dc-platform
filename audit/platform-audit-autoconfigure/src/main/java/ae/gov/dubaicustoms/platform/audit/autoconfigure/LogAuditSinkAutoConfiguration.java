package ae.gov.dubaicustoms.platform.audit.autoconfigure;

import ae.gov.dubaicustoms.platform.audit.log.LogAuditSink;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the log sink is on the classpath and dc.platform.audit.enabled != false.
 * Backs off when: an AuditSink is already defined — which includes the messaging and jdbc sinks, since
 *                 this config is @AutoConfigureAfter both. So the log sink is the always-available
 *                 fallback at the end of the messaging -> jdbc -> log degradation chain.
 * Beans: logAuditSink — the default structured-log AuditSink; needs no infrastructure.
 */
@AutoConfiguration
@AutoConfigureAfter(value = JdbcAuditSinkAutoConfiguration.class, name =
        "ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.MessagingAuditSinkAutoConfiguration")
@ConditionalOnClass(LogAuditSink.class)
@ConditionalOnProperty(prefix = "dc.platform.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LogAuditSinkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    LogAuditSink logAuditSink() {
        return new LogAuditSink();
    }
}
