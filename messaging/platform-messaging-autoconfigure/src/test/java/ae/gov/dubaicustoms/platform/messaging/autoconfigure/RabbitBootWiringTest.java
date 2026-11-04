package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.inmemory.InMemoryEventTransport;
import ae.gov.dubaicustoms.platform.messaging.rabbit.RabbitEventTransport;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Boot's real Rabbit auto-configuration with the platform's defaults, no mocks and no broker (connections are lazy): the
 * defaults must turn into the confirms, returns and mandatory the transport refuses to start without. Mocked wiring tests
 * could not see that the starter brought no {@code RabbitTemplate} at all on Boot 4 (HI-0083).
 */
class RabbitBootWiringTest {

    @Test
    void theDefaultsGiveBootsRabbitAutoConfigurationWhatTheTransportRequires() {
        new ApplicationContextRunner()
                .withInitializer(context -> new PlatformMessagingEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, PlatformMessagingAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(InMemoryEventTransport.class, KafkaTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EventTransport.class)).isInstanceOf(RabbitEventTransport.class);
                });
    }

    @Test
    void anApplicationThatTurnsConfirmsOffIsRefused() {
        new ApplicationContextRunner()
                .withInitializer(context -> new PlatformMessagingEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, PlatformMessagingAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(InMemoryEventTransport.class, KafkaTemplate.class))
                .withPropertyValues("spring.rabbitmq.publisher-confirm-type=none")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("spring.rabbitmq.publisher-confirm-type=correlated"));
    }
}
