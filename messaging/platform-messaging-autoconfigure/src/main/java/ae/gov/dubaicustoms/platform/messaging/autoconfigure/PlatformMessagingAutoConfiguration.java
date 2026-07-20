package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.DefaultEventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.EventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.JacksonEventSerializer;
import ae.gov.dubaicustoms.platform.messaging.inmemory.InMemoryEventTransport;
import ae.gov.dubaicustoms.platform.messaging.kafka.KafkaEventTransport;
import ae.gov.dubaicustoms.platform.messaging.rabbit.RabbitEventTransport;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

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
 *   Nested InMemoryTransportConfiguration/KafkaTransportConfiguration/RabbitTransportConfiguration
 *   additionally provide an EventTransport bean each (@ConditionalOnClass on the respective
 *   implementation/3rd-party template type, @ConditionalOnMissingBean(EventTransport.class) so a
 *   user bean always wins) — a same-capability autoconfigure -> impl edge, allowed by CLAUDE.md
 *   rule 5. Real applications bring exactly one of the three onto the classpath via their chosen
 *   starter, so at most one of these three ever activates.
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

    /** Provides the in-memory {@link EventTransport} when it is on the classpath and nothing else supplies one. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(InMemoryEventTransport.class)
    static class InMemoryTransportConfiguration {

        @Bean
        @ConditionalOnMissingBean(EventTransport.class)
        EventTransport inMemoryEventTransport() {
            return new InMemoryEventTransport();
        }
    }

    /** Provides the Kafka {@link EventTransport} when spring-kafka is present and nothing else supplies one. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    static class KafkaTransportConfiguration {

        @Bean
        @ConditionalOnMissingBean(EventTransport.class)
        @SuppressWarnings("unchecked")
        EventTransport kafkaEventTransport(
                KafkaTemplate<Object, Object> kafkaTemplate, ConsumerFactory<Object, Object> consumerFactory,
                MessagingProperties properties) {
            // Unchecked but safe: Boot's KafkaAutoConfiguration always types these <Object, Object>
            // (the real (de)serializers come from spring.kafka.{producer,consumer}.*-serializer,
            // which docs/modules/messaging.md instructs consumers to set to the byte[]/String pair
            // this transport expects); at runtime it's the same erased KafkaTemplate/ConsumerFactory.
            return new KafkaEventTransport((KafkaTemplate<String, byte[]>) (KafkaTemplate<?, ?>) kafkaTemplate,
                    (ConsumerFactory<String, byte[]>) (ConsumerFactory<?, ?>) consumerFactory,
                    properties.handler().retry().maxAttempts(), properties.handler().retry().backoff());
        }
    }

    /** Provides the Rabbit {@link EventTransport} when spring-rabbit is present and nothing else supplies one. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    static class RabbitTransportConfiguration {

        @Bean
        @ConditionalOnMissingBean(EventTransport.class)
        EventTransport rabbitEventTransport(
                RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, MessagingProperties properties) {
            return new RabbitEventTransport(rabbitTemplate, connectionFactory,
                    properties.handler().retry().maxAttempts(), properties.handler().retry().backoff(),
                    properties.rabbit().quorumQueues());
        }
    }
}
