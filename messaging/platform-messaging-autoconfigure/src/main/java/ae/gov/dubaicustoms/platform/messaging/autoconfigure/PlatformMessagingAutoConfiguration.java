package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.DefaultEventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.EventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.JacksonEventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/*
 * Activates when: EventTransport (platform-messaging-spi) on the classpath AND
 *   dc.platform.messaging.enabled != false
 * Backs off when: user defines an EventPublisher bean (publisher), an EventSerializer bean
 *   (serializer), or an EventHandlerRegistrar bean (handler dispatch)
 * Beans: platformEventSerializer — default Jackson JSON EventSerializer
 *   (@ConditionalOnMissingBean, @ConditionalOnClass(ObjectMapper.class)); eventPublisher — wraps
 *   the sole EventTransport bean with correlationId/eventType/eventVersion headers and an
 *   Observation (@ConditionalOnSingleCandidate(EventTransport.class): more than one transport bean
 *   is a configuration error the app must resolve, not something to silently pick a winner for);
 *   eventHandlerRegistrar — BeanPostProcessor scanning @EventHandler methods, subscribing each
 *   through the transport with a bounded retry + republish-to-DLQ wrapper and
 *   platform.messaging.handled metrics; messagingCapabilityDescriptor — ACTIVE with the transport's
 *   name() when exactly one EventTransport bean exists, else INACTIVE with no publisher/registrar
 *   at all (fail-at-injection is clearer than fail-at-boot when messaging is optional).
 * Order: none required.
 */
@AutoConfiguration
@ConditionalOnClass(EventTransport.class)
@ConditionalOnProperty(prefix = "dc.platform.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MessagingProperties.class)
public class PlatformMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ObjectMapper.class)
    EventSerializer platformEventSerializer(ObjectProvider<ObjectMapper> objectMapper) {
        return new JacksonEventSerializer(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    @ConditionalOnSingleCandidate(EventTransport.class)
    EventPublisher eventPublisher(EventTransport transport, EventSerializer serializer, MessagingProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry, ObjectProvider<MeterRegistry> meterRegistry) {
        return new DefaultEventPublisher(transport, serializer, properties,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP), meterRegistry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(EventTransport.class)
    EventHandlerRegistrar eventHandlerRegistrar(EventTransport transport, EventSerializer serializer,
            MessagingProperties properties, Environment environment, ObjectProvider<MeterRegistry> meterRegistry) {
        String group = environment.getProperty("spring.application.name", "application");
        return new EventHandlerRegistrar(transport, serializer, properties, group, meterRegistry.getIfAvailable());
    }

    @Bean
    CapabilityDescriptor messagingCapabilityDescriptor(ObjectProvider<EventTransport> transports) {
        EventTransport transport = transports.getIfUnique();
        return transport != null
                ? new CapabilityDescriptor("messaging", "ACTIVE", transport.name())
                : new CapabilityDescriptor("messaging", "INACTIVE", "no single EventTransport bean present");
    }
}
