package ae.gov.dubaicustoms.platform.security.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.JwtCurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.ProblemDetailAccessDeniedHandler;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.ProblemDetailAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/*
 * Activates when: servlet web application AND SecurityCustomizer (security-api) + HttpSecurity
 *                 (spring-security-config) on the classpath AND dc.platform.security.enabled != false
 * Backs off when: user defines a SecurityFilterChain bean, or dc.platform.security.mode=disabled
 * Beans: platformSecurityFilterChain — stateless JWT resource-server chain: security headers,
 *                 permit-paths open, everything else authenticated, RFC-9457-shaped 401/403
 *                 bodies (the errors capability's advice cannot reach exceptions the filter chain
 *                 raises before the DispatcherServlet runs), ordered SecurityCustomizers applied
 *                 last so applications can override the baseline;
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
@AutoConfiguration
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
            ObjectProvider<SecurityCustomizer> customizers) throws Exception {
        http.csrf(csrf -> csrf.disable()) // stateless bearer-token resource server, no cookie session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint())
                        .accessDeniedHandler(new ProblemDetailAccessDeniedHandler()));
        // Customizers run BEFORE the platform's own anyRequest() below: Spring Security forbids
        // adding authorizeHttpRequests() matchers after anyRequest(), so a customizer wanting to
        // permit an extra path must run while the registry is still open.
        for (SecurityCustomizer customizer : customizers.orderedStream().toList()) {
            customizer.customize(http);
        }
        http.authorizeHttpRequests(auth -> {
            properties.permitPaths().forEach(path -> auth.requestMatchers(path).permitAll());
            auth.anyRequest().authenticated();
        });
        return http.build();
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
}
