package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal.DefaultPlatformRestClientFactory;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal.OAuth2TokenRelayCustomizer;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

/*
 * Activates when: RestClient (spring-web) on the classpath AND dc.platform.restclient.enabled != false
 * Backs off when: user defines a PlatformRestClientFactory bean
 * Beans: platformRestClientFactory — DefaultPlatformRestClientFactory: JDK HttpClient request
 *                 factory with per-client connect/read timeouts, correlation-header propagation,
 *                 non-2xx responses mapped to RemoteCallException, http.client.requests
 *                 observations (client.name = platform client name) on the ObservationRegistry bean
 *                 when one exists (resolved lazily per builder; NOOP otherwise), ordered
 *                 PlatformRestClientCustomizers applied;
 *        restclientCapabilityDescriptor — one line in the startup capability banner;
 *        platformTokenRelayCustomizer (nested config) — relays the current bearer token to
 *                 outbound calls; activates ONLY when a Jwt-shaped resource server is on the
 *                 classpath AND the security capability's CurrentUserAccessor bean is actually
 *                 registered — a guarded, optional edge to the security capability's api
 *                 (CLAUDE.md rule 5), never a hard dependency for a restclient-only consumer.
 * Order: afterName PlatformSecurityAutoConfiguration (by string, not class literal: autoconfigure ->
 *        autoconfigure of another capability is forbidden) — @ConditionalOnBean(CurrentUserAccessor) is
 *        evaluated when this class is processed, so the security capability must register its accessor
 *        FIRST. Without the ordering the alphabetical default processes "restclient" before "security"
 *        and the token relay silently never registers in a real application.
 */
@AutoConfiguration(afterName = "ae.gov.dubaicustoms.platform.security.autoconfigure.PlatformSecurityAutoConfiguration")
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = "dc.platform.restclient", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RestClientProperties.class)
public class PlatformRestClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PlatformRestClientFactory platformRestClientFactory(RestClientProperties properties,
            ObjectProvider<PlatformRestClientCustomizer> customizers, ObjectProvider<ObservationRegistry> observationRegistry) {
        return new DefaultPlatformRestClientFactory(properties, customizers.orderedStream().toList(),
                () -> observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    CapabilityDescriptor restclientCapabilityDescriptor() {
        return new CapabilityDescriptor("restclient", "ACTIVE", "");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Jwt.class)
    static class TokenRelayConfiguration {

        @Bean
        @ConditionalOnBean(CurrentUserAccessor.class)
        PlatformRestClientCustomizer platformTokenRelayCustomizer() {
            return new OAuth2TokenRelayCustomizer();
        }
    }
}
