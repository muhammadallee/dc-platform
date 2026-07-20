package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

/** The mandatory 5-case ContextRunner matrix for PlatformSecurityAutoConfiguration. */
class PlatformSecurityAutoConfigurationTest {

    // ServletWebSecurityAutoConfiguration brings Boot's @EnableWebSecurity trigger, which is what
    // actually supplies the HttpSecurity bean our autoconfiguration's filter chain method depends
    // on; OAuth2ResourceServerAutoConfiguration supplies the JwtDecoder the chain resolves eagerly
    // at build time (a fixed jwk-set-uri never reaches the network: decoding is lazy).
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.test/jwks.json")
            .withConfiguration(AutoConfigurations.of(ServletWebSecurityAutoConfiguration.class,
                    OAuth2ResourceServerAutoConfiguration.class, PlatformSecurityAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasSingleBean(CurrentUserAccessor.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        // Boot's own SpringBootWebSecurityConfiguration still contributes its permissive
        // "defaultSecurityFilterChain" fallback once ours is absent; assert OUR bean is gone
        // rather than the type, and that the capability no longer reports itself active.
        runner.withPropertyValues("dc.platform.security.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("platformSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserBeanPresent() {
        var mine = org.mockito.Mockito.mock(SecurityFilterChain.class);
        runner.withBean("mine", SecurityFilterChain.class, () -> mine)
                .run(context -> assertThat(context.getBean(SecurityFilterChain.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(SecurityCustomizer.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformSecurityAutoConfiguration.class));
    }

    @Test
    void modeDisabledSkipsTheBaselineChain() {
        runner.withPropertyValues("dc.platform.security.mode=disabled")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("platformSecurityFilterChain");
                    assertThat(context).doesNotHaveBean(CurrentUserAccessor.class);
                    assertThat(context).hasSingleBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void inactiveOutsideWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformSecurityAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(SecurityFilterChain.class));
    }
}
