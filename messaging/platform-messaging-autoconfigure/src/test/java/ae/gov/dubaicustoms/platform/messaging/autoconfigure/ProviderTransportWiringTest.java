package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.messaging.kafka.KafkaEventTransport;
import ae.gov.dubaicustoms.platform.messaging.rabbit.RabbitEventTransport;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Verifies the nested {@code KafkaTransportConfiguration}/{@code RabbitTransportConfiguration}
 * each produce the expected {@link EventTransport} when their 3rd-party template/factory beans
 * exist and nothing else supplies an {@code EventTransport}.
 */
class ProviderTransportWiringTest {

    @Test
    @SuppressWarnings("unchecked")
    void kafkaTransportConfigurationSuppliesKafkaEventTransport() {
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(RabbitTemplate.class))
                .withBean(KafkaTemplate.class, () -> kafkaTemplate)
                .withBean(ConsumerFactory.class, () -> consumerFactory)
                .run(context -> assertThat(context).getBean(EventTransport.class).isInstanceOf(KafkaEventTransport.class));
    }

    @Test
    void rabbitTransportConfigurationSuppliesRabbitEventTransport() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(KafkaTemplate.class))
                .withBean(RabbitTemplate.class, () -> rabbitTemplate)
                .withBean(ConnectionFactory.class, () -> connectionFactory)
                .run(context -> assertThat(context).getBean(EventTransport.class).isInstanceOf(RabbitEventTransport.class));
    }

    @Test
    void userSuppliedEventTransportBacksOffBothProviderConfigurations() {
        EventTransport mine = new EventTransport() {
            @Override
            public String name() {
                return "mine";
            }

            @Override
            public void send(String destination, byte[] key, byte[] value, java.util.Map<String, String> headers) {
            }

            @Override
            public Subscription subscribe(String destination, String group, TransportListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class))
                .withBean(KafkaTemplate.class, () -> kafkaTemplate)
                .withBean(RabbitTemplate.class, () -> rabbitTemplate)
                .withBean("mine", EventTransport.class, () -> mine)
                .run(context -> assertThat(context.getBean(EventTransport.class)).isSameAs(mine));
    }
}
