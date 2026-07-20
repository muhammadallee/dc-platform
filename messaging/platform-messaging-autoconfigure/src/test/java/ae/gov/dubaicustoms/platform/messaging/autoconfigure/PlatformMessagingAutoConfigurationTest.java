package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal.EventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

/** The mandatory 5-case ContextRunner matrix for PlatformMessagingAutoConfiguration. */
class PlatformMessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class))
            .withBean(EventTransport.class, FakeEventTransport::new);

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context).hasSingleBean(EventHandlerRegistrar.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
            assertThat(context.getBean(CapabilityDescriptor.class).status()).isEqualTo("ACTIVE");
            assertThat(context.getBean(CapabilityDescriptor.class).detail()).isEqualTo("fake");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.messaging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EventPublisher.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        EventPublisher mine = (destination, event) -> { };
        runner.withBean("mine", EventPublisher.class, () -> mine)
                .run(context -> assertThat(context.getBean(EventPublisher.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(EventTransport.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformMessagingAutoConfiguration.class));
    }

    @Test
    void capabilityIsInactiveWhenNoTransportBeanPresent() {
        // Filters kafka/rabbit templates too: this module's own optional compile dependencies put
        // both on ITS classpath (needed to compile the nested transport configs), which a real
        // consumer never has together — each provider arrives via its own starter. Filtering both
        // reproduces the only combination a real app can be in with zero transports configured.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformMessagingAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(KafkaTemplate.class, RabbitTemplate.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EventPublisher.class);
                    assertThat(context).doesNotHaveBean(EventHandlerRegistrar.class);
                    assertThat(context.getBean(CapabilityDescriptor.class).status()).isEqualTo("INACTIVE");
                });
    }
}
