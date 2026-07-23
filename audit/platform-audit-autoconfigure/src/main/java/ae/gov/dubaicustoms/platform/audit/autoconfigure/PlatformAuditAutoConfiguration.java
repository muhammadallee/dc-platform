package ae.gov.dubaicustoms.platform.audit.autoconfigure;

import ae.gov.dubaicustoms.platform.audit.Auditor;
import ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.ActorResolver;
import ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.AsyncAuditor;
import ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.AuditedAdvisorFactory;
import ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.AuditedAutoProxyRegistrar;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;

/*
 * Activates when: an AuditSink bean exists (contributed by the messaging/jdbc/log sink configs, or by
 *                 the user) AND dc.platform.audit.enabled != false.
 * Backs off when: the user defines their own Auditor / ActorResolver / auditedAdvisor.
 * Beans: anonymousActorResolver — the actor fallback when the security capability is absent;
 *        auditor — the async Auditor over the selected sink, logging a startup WARN naming the sink;
 *        auditCapabilityDescriptor — one line in the startup capability banner naming the active sink;
 *        auditedAdvisor — a plain AOP advisor enforcing @Audited.
 * Order: after all sink and the security auto-configurations so their beans are registered first.
 */
@AutoConfiguration(
        after = {
                JdbcAuditSinkAutoConfiguration.class,
                LogAuditSinkAutoConfiguration.class,
                AuditSecurityAutoConfiguration.class},
        afterName =
                "ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.MessagingAuditSinkAutoConfiguration")
@ConditionalOnProperty(prefix = "dc.platform.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class PlatformAuditAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuditAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ActorResolver.class)
    ActorResolver anonymousActorResolver() {
        return () -> ActorResolver.ANONYMOUS;
    }

    @Bean
    @ConditionalOnBean(AuditSink.class)
    @ConditionalOnMissingBean
    Auditor auditor(AuditSink sink, AuditProperties properties) {
        // Startup WARN naming the active sink: the sink is chosen by a silent degradation chain
        // (messaging -> jdbc -> log), so operators must be able to see which one is actually live
        // without reading the banner (arch doc S7).
        log.warn("[DC-AUDIT-0100] audit active sink: {} (degradation chain messaging>jdbc>log)",
                describe(sink));
        return new AsyncAuditor(sink, properties.queueCapacity());
    }

    @Bean
    @ConditionalOnBean(AuditSink.class)
    CapabilityDescriptor auditCapabilityDescriptor(AuditSink sink) {
        return new CapabilityDescriptor("audit", "ACTIVE", describe(sink));
    }

    // Name the sink by its simple class name (JdbcAuditSink -> "jdbc").
    private static String describe(AuditSink sink) {
        return sink.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("auditsink", "");
    }

    /*
     * The @Audited advisor: a plain auto-proxy (decision D26) that records an AuditEvent around each
     * annotated method. Contributed only when an Auditor exists (i.e. a sink was selected). The actor
     * comes from the ActorResolver (security-backed when present, else anonymous); the event time from
     * a Clock bean when the application supplies one, else system-UTC.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(AuditedAutoProxyRegistrar.class)
    static class AuditedAdvisorConfiguration {

        // ROLE_INFRASTRUCTURE: the InfrastructureAdvisorAutoProxyCreator only considers Advisor beans
        // with this role — a plain application-role bean would be silently ignored (decision D26).
        // Gated on AuditSink (contributed by a separate, earlier auto-config) rather than Auditor:
        // @ConditionalOnBean cannot reliably see a bean declared in the same auto-config as this nested
        // one. Injecting the Auditor forces it to be created first — it exists whenever a sink does.
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnBean(AuditSink.class)
        @ConditionalOnMissingBean(name = "auditedAdvisor")
        Advisor auditedAdvisor(Auditor auditor, ActorResolver actorResolver, ObjectProvider<Clock> clock) {
            return AuditedAdvisorFactory.create(auditor, actorResolver, clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
