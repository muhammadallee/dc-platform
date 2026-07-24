package ae.gov.dubaicustoms.platform.test.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * Fluent builder for a JWT bearer identity in {@code MockMvc} tests, sugar over spring-security-test's
 * {@link SecurityMockMvcRequestPostProcessors#jwt()}. The platform security capability is an OAuth2
 * resource server, so a test authenticates by attaching a synthetic {@code Jwt} rather than a real
 * token.
 *
 * <pre>{@code
 * mvc.perform(post("/orders")
 *         .with(TestTokens.user("alice").roles("ADMIN").claim("tenant", "dxb").jwt()))
 *    .andExpect(status().isCreated());
 * }</pre>
 *
 * <p>{@code roles(...)} adds {@code ROLE_}-prefixed authorities (matching Spring Security's
 * {@code hasRole} convention); {@code authority(...)} adds authorities verbatim. Each {@link #jwt()}
 * builds an independent post-processor, so a single builder can seed several requests. Not thread-safe;
 * confine a builder to one test.
 *
 * @since 0.2.0
 */
public final class TestTokens {

    private TestTokens() {
    }

    /**
     * Starts building a token for the given subject (also set as the {@code preferred_username} claim).
     *
     * @param subject the token subject / user id; never {@code null}
     * @return a new builder
     */
    public static Builder user(String subject) {
        return new Builder(Objects.requireNonNull(subject, "subject must not be null"));
    }

    /**
     * Mutable builder for a JWT {@link JwtRequestPostProcessor}.
     *
     * @since 0.2.0
     */
    public static final class Builder {

        private final String subject;
        private final List<GrantedAuthority> authorities = new ArrayList<>();
        private final Map<String, Object> claims = new LinkedHashMap<>();

        private Builder(String subject) {
            this.subject = subject;
        }

        /**
         * Adds {@code ROLE_}-prefixed authorities for each role (Spring Security's {@code hasRole}
         * convention).
         *
         * @param roles the role names, without the {@code ROLE_} prefix; never {@code null}
         * @return this builder
         */
        public Builder roles(String... roles) {
            for (String role : Objects.requireNonNull(roles, "roles must not be null")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return this;
        }

        /**
         * Adds authorities verbatim (no prefix), for scope-style or custom authorities.
         *
         * @param names the authority strings; never {@code null}
         * @return this builder
         */
        public Builder authority(String... names) {
            for (String name : Objects.requireNonNull(names, "names must not be null")) {
                authorities.add(new SimpleGrantedAuthority(name));
            }
            return this;
        }

        /**
         * Adds a custom claim to the token.
         *
         * @param name the claim name; never {@code null}
         * @param value the claim value; never {@code null}
         * @return this builder
         */
        public Builder claim(String name, Object value) {
            claims.put(Objects.requireNonNull(name, "name must not be null"),
                    Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        /**
         * Builds a {@code MockMvc} request post-processor that authenticates the request with the
         * configured subject, authorities, and claims.
         *
         * @return a {@link JwtRequestPostProcessor}; never {@code null}
         */
        public JwtRequestPostProcessor jwt() {
            return SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(builder -> {
                        builder.subject(subject);
                        builder.claim("preferred_username", subject);
                        claims.forEach(builder::claim);
                    })
                    .authorities(List.copyOf(authorities));
        }
    }
}
