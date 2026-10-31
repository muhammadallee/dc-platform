package ae.gov.dubaicustoms.platform.events.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.events.autoconfigure.internal.DomainEventRelay;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.autoconfigure.PlatformMessagingAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The relay's bean condition needs messaging evaluated first. Boot sorts unordered auto-configurations alphabetically,
 * and "events" sorts before "messaging": without an explicit order the relay was silently absent, and business commits
 * produced no integration event. So both come from real auto-configuration here, not from a pre-registered bean.
 */
class RelayOrderingTest {

    @Test
    void theRelayIsPresentWithAutoConfiguredMessaging() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformEventsAutoConfiguration.class,
                        PlatformMessagingAutoConfiguration.class))
                .withPropertyValues("dc.platform.events.relay.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EventPublisher.class);
                    assertThat(context).hasSingleBean(DomainEventRelay.class);
                });
    }
}
