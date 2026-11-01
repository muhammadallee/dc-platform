package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.EventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlatformMessagingDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class));

    @Test
    void propertiesDefaultSnapshot() {
        // §7.2: a changed default fails here until the snapshot is consciously updated (CHANGELOG).
        runner.withClassLoader(new FilteredClassLoader(org.springframework.kafka.core.KafkaTemplate.class,
                org.springframework.amqp.rabbit.core.RabbitTemplate.class)).run(context -> {
            var properties = context.getBean(MessagingProperties.class);
            assertThat(properties.handler().retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.handler().retry().backoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.handler().retry().maxBackoff()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.rabbit().quorumQueues()).isTrue();
            assertThat(properties.rabbit().confirmTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.rabbit().prefetch()).isEqualTo(10);
            assertThat(properties.rabbit().shutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.rabbit().deliveryLimit()).isEqualTo(5);
        });
    }

    @Test
    void itStartsWithoutMicrometerOrTracingOrJackson() {
        // HI-0080: a missing optional jar named in an always-present bean's signature failed startup.
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class, ObservationRegistry.class, Tracer.class,
                ObjectMapper.class, org.springframework.kafka.core.KafkaTemplate.class,
                org.springframework.amqp.rabbit.core.RabbitTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EventTransport.class);
                    assertThat(context).hasSingleBean(EventHandlerRegistrar.class);
                });
    }
}
