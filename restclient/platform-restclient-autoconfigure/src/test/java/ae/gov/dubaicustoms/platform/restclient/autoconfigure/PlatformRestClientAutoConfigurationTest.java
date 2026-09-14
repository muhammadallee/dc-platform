package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.autoconfigure.PlatformSecurityAutoConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
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
    void invalidTimeoutFailsStartupInsteadOfFallingBackToADefault() {
        runner.withPropertyValues("dc.platform.restclient.clients.orders.read-timeout=soon")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("soon"));
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

    @Test
    void tokenRelayRegistersUnderTheRealAutoConfigurationOrderWithTheSecurityCapability() {
        // Both auto-configurations sorted exactly as Boot sorts them in an application. A user-supplied
        // accessor (above) is registered before any auto-configuration and hides ordering bugs; here the
        // accessor comes from PlatformSecurityAutoConfiguration itself. The user chain only stands in for
        // HttpSecurity, which this runner does not provide; the accessor does not depend on it.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformRestClientAutoConfiguration.class, PlatformSecurityAutoConfiguration.class))
                .withBean(SecurityFilterChain.class, () -> new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE))
                .run(context -> {
                    assertThat(context).hasSingleBean(CurrentUserAccessor.class);
                    assertThat(context).hasBean("platformTokenRelayCustomizer");
                });
    }
}
