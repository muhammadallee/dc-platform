package ae.gov.dubaicustoms.platform.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Extension point: further configure the platform's baseline {@link HttpSecurity} chain.
 * Contribute beans of this type; the platform applies them in {@code @Order} after its own
 * baseline configuration (stateless JWT resource server, permit-paths, method security).
 *
 * <pre>{@code
 * @Bean
 * @Order(10)
 * SecurityCustomizer publicOrdersEndpoint() {
 *     return http -> http.authorizeHttpRequests(auth -> auth
 *             .requestMatchers("/orders/public/**").permitAll());
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe; they run once at context startup. New {@code default}
 * methods may be added in minor releases.
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface SecurityCustomizer {

    /**
     * Adjusts the platform's baseline security chain.
     *
     * @param http the security builder; never {@code null}
     * @throws Exception propagated from {@link HttpSecurity} builder methods, which declare it
     */
    void customize(HttpSecurity http) throws Exception;
}
