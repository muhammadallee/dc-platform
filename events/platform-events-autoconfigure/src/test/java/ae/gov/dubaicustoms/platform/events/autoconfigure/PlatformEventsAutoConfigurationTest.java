package ae.gov.dubaicustoms.platform.events.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.events.DomainEvent;
import ae.gov.dubaicustoms.platform.events.DomainEventPublisher;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.AfterCommitDispatcher;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DomainEventHandlerRegistrar;
import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DomainEventRelay;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The mandatory 5-case ContextRunner matrix for PlatformEventsAutoConfiguration. */
class PlatformEventsAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PlatformEventsAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DomainEventPublisher.class);
            assertThat(context).hasSingleBean(DomainEventHandlerRegistrar.class);
            assertThat(context).hasSingleBean(AfterCommitDispatcher.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
            assertThat(context.getBean(CapabilityDescriptor.class).status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.events.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DomainEventPublisher.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        DomainEventPublisher mine = event -> { };
        runner.withBean("mine", DomainEventPublisher.class, () -> mine)
                .run(context -> assertThat(context.getBean(DomainEventPublisher.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(DomainEvent.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformEventsAutoConfiguration.class));
    }

    @Test
    void relayStaysInactiveWithoutOptInEvenWhenEventPublisherBeanExists() {
        EventPublisher eventPublisher = (destination, event) -> { };
        runner.withBean(EventPublisher.class, () -> eventPublisher)
                .run(context -> assertThat(context).doesNotHaveBean(DomainEventRelay.class));
    }

    @Test
    void relayActivatesWhenOptedInAndEventPublisherBeanExists() {
        EventPublisher eventPublisher = (destination, event) -> { };
        runner.withBean(EventPublisher.class, () -> eventPublisher)
                .withPropertyValues("dc.platform.events.relay.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DomainEventRelay.class));
    }

    @Test
    void relayStaysInactiveWhenOptedInButNoEventPublisherBeanExists() {
        runner.withPropertyValues("dc.platform.events.relay.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DomainEventRelay.class));
    }
}
