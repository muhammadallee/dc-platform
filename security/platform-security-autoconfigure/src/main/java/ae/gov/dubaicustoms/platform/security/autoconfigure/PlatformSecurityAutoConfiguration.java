package ae.gov.dubaicustoms.platform.security.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.AnonymousAccessProbe;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.FilterChainCoverageCheck;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.JwtCurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.PlatformJwtAuthoritiesConverter;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.PlatformJwtDecoderFactory;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.ProblemDetailAccessDeniedHandler;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.ProblemDetailAuthenticationEntryPoint;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.SecurityMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

/*
 * Activates when: servlet web application AND SecurityCustomizer (security-api) + HttpSecurity
 *                 (spring-security-config) on the classpath AND dc.platform.security.enabled != false
 * Backs off when: user defines a SecurityFilterChain bean, or dc.platform.security.mode=disabled
 * Beans: platformSecurityFilterChain — stateless JWT resource-server chain: security headers,
 *                 permit-paths open, everything else authenticated, RFC-9457-shaped 401/403
 *                 bodies (the errors capability's advice cannot reach exceptions the filter chain
 *                 raises before the DispatcherServlet runs) for BOTH missing credentials and
 *                 rejected bearer tokens (the resource server has its own entry point), problem
 *                 type = dc.platform.errors.type-base-uri + code, ordered SecurityCustomizers applied
 *                 last so applications can override the baseline;
 *        jwtDecoder — issuer, audience, expiry and subject required, algorithm allow-list, bounded skew, a bounded, cached
 *                 and rate-limited JWKS source; only when the baseline chain is in use or the resource-server keys are set,
 *                 and a setup that would accept tokens it should not fails startup. Before Boot's, so Boot's backs off;
 *        jwtAuthenticationConverter — scopes and Keycloak or plain roles as authorities;
 *        filterChainCoverageCheck — fails startup if a request could match no chain, which would leave it unsecured;
 *        currentUserAccessor — JwtCurrentUserAccessor reading the Jwt principal;
 *        securityCapabilityDescriptor — one line in the startup capability banner
 * Order: none required; Spring Boot's own SecurityAutoConfiguration/SecurityFilterAutoConfiguration
 *        back off automatically once any SecurityFilterChain bean exists (Boot's standard pattern).
 *
 * Secure-by-default: mode=disabled is explicit-only configuration, never implied by a profile
 * (e.g. "local") -- an accidentally-active profile in a real environment must not silently turn
 * security off. Tests use spring-security-test's jwt() request post-processor (no live IdP
 * needed); docs/modules/security.md documents a Docker dev-issuer option for manual runs.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.security.oauth2.server.resource.autoconfigure"
        + ".OAuth2ResourceServerAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityCustomizer.class, HttpSecurity.class})
@ConditionalOnProperty(prefix = "dc.platform.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    @ConditionalOnProperty(prefix = "dc.platform.security", name = "mode", havingValue = "resource-server", matchIfMissing = true)
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
            ObjectProvider<SecurityCustomizer> customizers, SecurityMetrics metrics, Environment environment,
            ObjectProvider<ServletContext> servletContext) throws Exception {
        // The errors capability owns the problem-type namespace; read its key (same default) so 401/403
        // types match every other platform error without depending on the errors module.
        String typeBaseUri = environment.getProperty("dc.platform.errors.type-base-uri", "urn:dc:problem:");
        ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(typeBaseUri, metrics);
        ProblemDetailAccessDeniedHandler deniedHandler = new ProblemDetailAccessDeniedHandler(typeBaseUri);
        http.csrf(csrf -> csrf.disable()) // stateless bearer-token resource server, no cookie session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                // By default a key source outage escapes the filter as a bare 500.
                                filter.setAuthenticationFailureHandler((request, response, failure) -> {
                                    if (failure instanceof AuthenticationServiceException) {
                                        entryPoint.unavailable(request, response);
                                    } else {
                                        entryPoint.commence(request, response, failure);
                                    }
                                });
                                return filter;
                            }
                        }))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        // Customizers run BEFORE the platform's own anyRequest() below: Spring Security forbids
        // adding authorizeHttpRequests() matchers after anyRequest(), so a customizer wanting to
        // permit an extra path must run while the registry is still open.
        for (SecurityCustomizer customizer : customizers.orderedStream().toList()) {
            customizer.customize(http);
        }
        http.authorizeHttpRequests(auth -> {
            // The servlet error page renders a failure of a request that was already authorized (or
            // rejected) on its REQUEST dispatch; authorizing the ERROR dispatch again would replace a
            // filter's 409 or a filter-chain 500 with a misleading 401 for anonymous callers.
            auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
            properties.permitPaths().forEach(path -> auth.requestMatchers(path).permitAll());
            auth.anyRequest().authenticated();
        });
        SecurityFilterChain chain = http.build();
        AnonymousAccessProbe.check(chain, servletContext.getIfAvailable());
        return chain;
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnProperty(prefix = "dc.platform.security", name = "mode", havingValue = "resource-server", matchIfMissing = true)
    @Conditional(DecoderWanted.class)
    JwtDecoder jwtDecoder(Environment environment, SecurityProperties properties, SecurityMetrics metrics) {
        return PlatformJwtDecoderFactory.create(environment, properties.jwt(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean(JwtAuthenticationConverter.class)
    @ConditionalOnProperty(prefix = "dc.platform.security", name = "mode", havingValue = "resource-server", matchIfMissing = true)
    JwtAuthenticationConverter jwtAuthenticationConverter(SecurityProperties properties, Environment environment) {
        String client = properties.jwt().resourceClient();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new PlatformJwtAuthoritiesConverter(
                client.isBlank() ? environment.getProperty("spring.application.name") : client));
        return converter;
    }

    @Bean
    static FilterChainCoverageCheck filterChainCoverageCheck(ObjectProvider<FilterChainProxy> proxy, Environment environment) {
        return new FilterChainCoverageCheck(proxy,
                environment.getProperty("dc.platform.security.allow-partial-chains", Boolean.class, false));
    }

    @Bean
    @ConditionalOnMissingBean(CurrentUserAccessor.class)
    @ConditionalOnProperty(prefix = "dc.platform.security", name = "mode", havingValue = "resource-server", matchIfMissing = true)
    CurrentUserAccessor currentUserAccessor() {
        return new JwtCurrentUserAccessor();
    }

    @Bean
    CapabilityDescriptor securityCapabilityDescriptor(SecurityProperties properties) {
        return new CapabilityDescriptor("security", "ACTIVE", properties.mode().name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Micrometer is optional, so its types stay in a class only loaded when it is there. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerSecurityMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityMetrics.class)
        SecurityMetrics securityMetrics(ObjectProvider<MeterRegistry> registries) {
            return new SecurityMetrics() {
                @Override
                public void tokenRejected() {
                    // The primary registry only: with a composite, its children would count twice.
                    registries.ifUnique(registry -> Counter.builder("platform.security.token.rejected")
                            .description("Bearer tokens refused").register(registry).increment());
                }

                @Override
                public void jwksFetch(boolean success) {
                    registries.ifUnique(registry -> Counter.builder("platform.security.jwks.fetch")
                            .description("JWKS fetches").tag("outcome", success ? "success" : "failure").register(registry).increment());
                }
            };
        }
    }

    @Bean
    @ConditionalOnMissingBean(SecurityMetrics.class)
    SecurityMetrics noSecurityMetrics() {
        return SecurityMetrics.NONE;
    }

    /** The baseline chain needs a decoder. A consumer's own chain wants one only if the resource-server keys are set. */
    static final class DecoderWanted extends AnyNestedCondition {

        DecoderWanted() {
            super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnMissingBean(SecurityFilterChain.class)
        static class NoConsumerChain {
        }

        @ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "issuer-uri")
        static class Issuer {
        }

        @ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "jwk-set-uri")
        static class Jwks {
        }
    }
}
