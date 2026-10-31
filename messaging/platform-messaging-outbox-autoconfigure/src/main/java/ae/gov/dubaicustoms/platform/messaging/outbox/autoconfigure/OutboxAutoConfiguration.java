package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.outbox.JdbcOutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxContext;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxDrainer;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.AfterCommitOutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.OutboxConnections;
import ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal.PlatformOutboxContext;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * Transactional outbox (HD11). Activation: platform-messaging-outbox on the classpath, a DataSource bean, and
 * dc.platform.messaging.enabled not false. Tier DEFAULT_ON (plan §7.3):
 *   dc.platform.messaging.outbox.enabled=true (default): outboxPublisher writes in the caller's transaction;
 *   outboxDrainer (SmartLifecycle) delivers; outboxCapabilityDescriptor reports ACTIVE.
 *   dc.platform.messaging.outbox.enabled=false: a startup WARN says publishes are no longer guaranteed;
 *   outboxPublisher publishes through EventPublisher after commit; no drainer runs.
 * Backs off by bean: OutboxPublisher, OutboxContext, outboxDrainer, outboxCapabilityDescriptor.
 * Deliberately adds no DataSource and no PlatformTransactionManager bean: either would make Boot back off from the
 * service's own pool or JPA transaction manager (OutboxConnections holds the drainer's pool instead).
 */
@AutoConfiguration(afterName = {
        "ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ConditionalOnClass({JdbcOutboxPublisher.class, DataSource.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "dc.platform.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dc.platform.messaging.outbox", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class Enabled {

        @Bean
        @ConditionalOnMissingBean
        OutboxContext outboxContext(ObjectProvider<PlatformOutboxContext.TraceSource> trace,
                ObjectProvider<PlatformOutboxContext.PrincipalSource> principal) {
            return new PlatformOutboxContext(trace.getIfAvailable(() -> java.util.Map::of),
                    principal.getIfAvailable(() -> () -> null));
        }

        @Bean
        @ConditionalOnMissingBean
        OutboxPublisher outboxPublisher(DataSource dataSource, EventSerializer serializer, OutboxContext context) {
            return new JdbcOutboxPublisher(dataSource, serializer, context, Clock.systemUTC());
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        OutboxConnections outboxConnections(DataSource dataSource, OutboxProperties properties,
                ObjectProvider<MeterRegistry> registry) {
            return OutboxConnections.of(dataSource, properties.poolSize(), registry.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean
        OutboxDrainer outboxDrainer(OutboxConnections connections, EventTransport transport, OutboxContext context,
                OutboxProperties properties, ObjectProvider<MeterRegistry> registry) {
            return new OutboxDrainer(connections.dataSource(), transport, context, properties.settings(),
                    Clock.systemUTC(), registry.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean(name = "outboxCapabilityDescriptor")
        CapabilityDescriptor outboxCapabilityDescriptor() {
            return new CapabilityDescriptor("outbox", "ACTIVE", "jdbc");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dc.platform.messaging.outbox", name = "enabled", havingValue = "false")
    static class Disabled {

        @Bean
        @ConditionalOnMissingBean
        OutboxPublisher outboxPublisher(EventPublisher eventPublisher) {
            log.warn("dc.platform.messaging.outbox.enabled=false: events are published after commit and are NO LONGER "
                    + "GUARANTEED. A crash between the commit and the send loses the event.");
            return new AfterCommitOutboxPublisher(eventPublisher);
        }

        @Bean
        @ConditionalOnMissingBean(name = "outboxCapabilityDescriptor")
        CapabilityDescriptor outboxCapabilityDescriptor() {
            return new CapabilityDescriptor("outbox", "INACTIVE", "disabled: publishes are not guaranteed");
        }
    }

    /** The current trace as W3C headers, when tracing is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Tracer.class, Propagator.class})
    static class TraceConfiguration {

        @Bean
        @ConditionalOnBean({Tracer.class, Propagator.class})
        PlatformOutboxContext.TraceSource outboxTraceSource(Tracer tracer, Propagator propagator) {
            return PlatformOutboxContext.traceSource(tracer, propagator);
        }
    }

    /** The current principal's subject, when the security API is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CurrentUserAccessor.class)
    static class PrincipalConfiguration {

        @Bean
        @ConditionalOnBean(CurrentUserAccessor.class)
        PlatformOutboxContext.PrincipalSource outboxPrincipalSource(CurrentUserAccessor accessor) {
            Supplier<String> subject = () -> accessor.currentUser().map(user -> user.subject()).orElse(null);
            return subject::get;
        }
    }
}
