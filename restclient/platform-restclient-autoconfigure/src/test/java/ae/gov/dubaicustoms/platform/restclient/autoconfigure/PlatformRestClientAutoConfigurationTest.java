package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/** The mandatory 5-case ContextRunner matrix for PlatformRestClientAutoConfiguration, plus the token-relay guard. */
class PlatformRestClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformRestClientAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PlatformRestClientFactory.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.restclient.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformRestClientFactory.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        PlatformRestClientFactory mine = name -> RestClient.builder();
        runner.withBean("mine", PlatformRestClientFactory.class, () -> mine)
                .run(context -> assertThat(context.getBean(PlatformRestClientFactory.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(RestClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformRestClientAutoConfiguration.class));
    }

    @Test
    void propertiesBindFromKebabKeys() {
        runner.withPropertyValues(
                        "dc.platform.restclient.propagate-correlation=false",
                        "dc.platform.restclient.defaults.connect-timeout=5s",
                        "dc.platform.restclient.clients.orders.read-timeout=1s")
                .run(context -> {
                    RestClientProperties properties = context.getBean(RestClientProperties.class);
                    assertThat(properties.propagateCorrelation()).isFalse();
                    assertThat(properties.defaults().connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
                    assertThat(properties.timeoutsFor("orders").readTimeout()).isEqualTo(java.time.Duration.ofSeconds(1));
                });
    }

    @Test
    void tokenRelayCustomizerBacksOffWithoutCurrentUserAccessor() {
        runner.run(context -> assertThat(context).doesNotHaveBean("platformTokenRelayCustomizer"));
    }

    @Test
    void tokenRelayCustomizerRegistersWhenCurrentUserAccessorPresent() {
        CurrentUserAccessor accessor = () -> Optional.<CurrentUser>empty();
        runner.withBean(CurrentUserAccessor.class, () -> accessor)
                .run(context -> assertThat(context).hasBean("platformTokenRelayCustomizer"));
    }
}
