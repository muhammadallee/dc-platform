package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.DefaultEventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.EventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.JacksonEventSerializer;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.MessageTrace;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.MessagingInstrumentation;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.MicrometerMessagingInstrumentation;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.TracingMessageTrace;
import ae.gov.dubaicustoms.platform.messaging.inmemory.InMemoryEventTransport;
import ae.gov.dubaicustoms.platform.messaging.kafka.KafkaEventTransport;
import ae.gov.dubaicustoms.platform.messaging.rabbit.RabbitEventTransport;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
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

    /** Jackson, when present. A nested class: its type in a method signature of the outer one breaks reflection without it. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObjectMapper.class)
    static class SerializerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        EventSerializer platformEventSerializer(ObjectProvider<ObjectMapper> objectMapper) {
            return new JacksonEventSerializer(objectMapper.getIfAvailable(ObjectMapper::new));
        }
    }

    /** Micrometer, when present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MeterRegistry.class, ObservationRegistry.class})
    static class InstrumentationConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MessagingInstrumentation messagingInstrumentation(ObjectProvider<MeterRegistry> meters,
                ObjectProvider<ObservationRegistry> observations) {
            return new MicrometerMessagingInstrumentation(meters::getIfAvailable, observations::getIfAvailable);
        }
    }

    /** Micrometer Tracing, when present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Tracer.class, Propagator.class})
    static class TraceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MessageTrace messageTrace(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
            return new TracingMessageTrace(tracer::getIfUnique, propagator::getIfUnique);
        }
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    @ConditionalOnSingleCandidate(EventTransport.class)
    EventPublisher eventPublisher(EventTransport transport, ObjectProvider<EventSerializer> serializer,
            ObjectProvider<MessagingInstrumentation> instrumentation, ObjectProvider<MessageTrace> trace,
            Environment environment) {
        return new DefaultEventPublisher(transport, serializer::getObject,
                instrumentation.getIfAvailable(() -> MessagingInstrumentation.NOOP),
                trace.getIfAvailable(() -> MessageTrace.NONE), environment.getProperty("spring.application.name", "application"));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(EventTransport.class)
    static EventHandlerRegistrar eventHandlerRegistrar(ObjectProvider<EventTransport> transport,
            ObjectProvider<EventSerializer> serializer, ObjectProvider<MessagingProperties> properties,
            Environment environment, ObjectProvider<MessagingInstrumentation> instrumentation,
            ObjectProvider<MessageTrace> trace) {
        // Static and lazy: a BeanPostProcessor must not pull anything in while post-processors are being registered, or
        // what it obtains skips post-processing. An early MeterRegistry costs the service its meters and common tags.
        String group = environment.getProperty("spring.application.name", "application");
        return new EventHandlerRegistrar(transport::getObject, serializer::getObject, properties::getObject, group,
                () -> instrumentation.getIfAvailable(() -> MessagingInstrumentation.NOOP),
                () -> trace.getIfAvailable(() -> MessageTrace.NONE));
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
                RabbitTemplate rabbitTemplate, ConnectionFactory connectionFactory, MessagingProperties properties,
                ObjectProvider<MessageTrace> trace) {
            MessagingProperties.Rabbit rabbit = properties.rabbit();
            var transport = new RabbitEventTransport(rabbitTemplate, connectionFactory, rabbit.quorumQueues(),
                    rabbit.confirmTimeout(), rabbit.prefetch(), rabbit.shutdownTimeout(), rabbit.deliveryLimit());
            transport.carryContextWith(action -> trace.getIfAvailable(() -> MessageTrace.NONE).carry(action));
            return transport;
        }
    }
}
