package ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure;

import ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.internal.MessagingAuditSink;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/*
 * Activates when: the messaging capability's EventPublisher is on the classpath, a bean of it exists,
 *                 and dc.platform.audit.enabled != false.
 * Backs off when: an AuditSink is already defined (the user's, or none — this is the top priority).
 * Beans: messagingAuditSink — publishes audit events to the messaging transport (dc.audit).
 * Order: the top link in the messaging -> jdbc -> log degradation chain; the jdbc and log sink
 *        auto-configurations (in platform-audit-autoconfigure) are @AutoConfigureAfter this by name,
 *        so they contribute only when it does not. Guarded reference to another capability's api
 *        (CLAUDE.md rule 5, decision D53).
 */
@AutoConfiguration
@ConditionalOnClass(EventPublisher.class)
@ConditionalOnProperty(prefix = "dc.platform.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MessagingAuditSinkAutoConfiguration {

    @Bean
    @ConditionalOnBean(EventPublisher.class)
    @ConditionalOnMissingBean(AuditSink.class)
    MessagingAuditSink messagingAuditSink(EventPublisher publisher) {
        return new MessagingAuditSink(publisher);
    }
}
