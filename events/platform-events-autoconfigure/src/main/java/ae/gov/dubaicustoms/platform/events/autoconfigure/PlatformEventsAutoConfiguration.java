package ae.gov.dubaicustoms.platform.events.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventPublisher;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.AfterCommitDispatcher;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DefaultDomainEventPublisher;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DomainEventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DomainEventRelay;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.ImmediateDispatcher;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.TransactionalDispatcher;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * Activates when: DomainEvent (platform-events-api) on the classpath AND
 *   dc.platform.events.enabled != false
 * Backs off when: user defines a DomainEventPublisher bean, or an AfterCommitDispatcher bean
 * Beans: platformDomainEventPublisher — bridges to Spring's ApplicationEventPublisher;
 *   domainEventHandlerRegistrar — BeanPostProcessor + ApplicationListener scanning
 *   @DomainEventHandler methods, dispatching each via the resolved AfterCommitDispatcher;
 *   afterCommitDispatcher — TransactionalDispatcher when spring-tx is present
 *   (@ConditionalOnClass(TransactionSynchronizationManager.class)), else ImmediateDispatcher
 *   (@ConditionalOnMissingBean); domainEventRelay — only when platform-messaging-api's
 *   EventPublisher is on the classpath AND an EventPublisher bean actually exists
 *   (@ConditionalOnBean) AND dc.platform.events.relay.enabled=true: re-publishes
 *   @EventType-annotated domain events as integration events after commit; eventsCapabilityDescriptor.
 * Order: none required.
 */
@AutoConfiguration
@ConditionalOnClass(DomainEvent.class)
@ConditionalOnProperty(prefix = "dc.platform.events", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EventsProperties.class)
public class PlatformEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DomainEventPublisher platformDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new DefaultDomainEventPublisher(applicationEventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    DomainEventHandlerRegistrar domainEventHandlerRegistrar(AfterCommitDispatcher dispatcher) {
        return new DomainEventHandlerRegistrar(dispatcher);
    }

    @Bean
    CapabilityDescriptor eventsCapabilityDescriptor() {
        return new CapabilityDescriptor("events", "ACTIVE", "");
    }

    /** Dispatches after commit when a transaction is active on the current thread. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(TransactionSynchronizationManager.class)
    static class TransactionalDispatcherConfiguration {

        @Bean
        @ConditionalOnMissingBean(AfterCommitDispatcher.class)
        AfterCommitDispatcher transactionalAfterCommitDispatcher() {
            return new TransactionalDispatcher();
        }
    }

    /** Falls back to immediate dispatch when spring-tx isn't on the classpath at all. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.transaction.support.TransactionSynchronizationManager")
    static class ImmediateDispatcherConfiguration {

        @Bean
        @ConditionalOnMissingBean(AfterCommitDispatcher.class)
        AfterCommitDispatcher immediateAfterCommitDispatcher() {
            return new ImmediateDispatcher();
        }
    }

    /**
     * Re-publishes {@code @EventType}-annotated domain events as integration events, opt-in
     * (kill-switch default {@code false}) and only when messaging is actually configured.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EventPublisher.class)
    @ConditionalOnProperty(prefix = "dc.platform.events.relay", name = "enabled", havingValue = "true")
    static class RelayConfiguration {

        @Bean
        @ConditionalOnBean(EventPublisher.class)
        @ConditionalOnMissingBean
        DomainEventRelay domainEventRelay(
                EventPublisher eventPublisher, AfterCommitDispatcher dispatcher, EventsProperties properties) {
            return new DomainEventRelay(eventPublisher, dispatcher, properties.relay().destinationPrefix());
        }
    }
}
