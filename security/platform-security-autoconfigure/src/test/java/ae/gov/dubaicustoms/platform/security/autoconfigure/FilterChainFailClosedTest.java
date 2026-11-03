package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import ae.gov.dubaicustoms.platform.security.autoconfigure.internal.InvalidSecurityConfigurationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Setups that would leave a request unsecured, or tokens unchecked, do not start. */
class FilterChainFailClosedTest {

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void aConsumerChainThatDoesNotCoverEverythingFailsStartup() {
        assertThatThrownBy(() -> start(NarrowChainApp.class).close())
                .isInstanceOf(InvalidSecurityConfigurationException.class)
                .hasMessageContaining("does not match every request");
    }

    @Test
    void itCanBeAcceptedExplicitly() throws Exception {
        try (ConfigurableApplicationContext app = start(NarrowChainApp.class, "dc.platform.security.allow-partial-chains=true")) {
            assertThat(status(app, "/open")).isEqualTo(200);
        }
    }

    @Test
    void aFullConsumerChainReplacesThePlatformsAndNeedsNoIssuer() throws Exception {
        try (ConfigurableApplicationContext app = start(FullChainApp.class)) {
            assertThat(app.containsBean("platformSecurityFilterChain")).isFalse();
            assertThat(app.getBeanNamesForType(JwtDecoder.class)).isEmpty();
            assertThat(status(app, "/open")).isEqualTo(200);
            assertThat(status(app, "/closed")).isEqualTo(403);
        }
    }

    @Test
    void aFinalCatchAllAfterANarrowChainIsFine() throws Exception {
        try (ConfigurableApplicationContext app = start(TwoChainsApp.class)) {
            assertThat(status(app, "/open")).isEqualTo(200);
            assertThat(status(app, "/closed")).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @CsvSource({"dc.platform.security.mode=disabled", "dc.platform.security.enabled=false"})
    void switchedOffWithNoChainStaysClosed(String property) throws Exception {
        try (ConfigurableApplicationContext app = start(App.class, property)) {
            assertThat(status(app, "/open")).isEqualTo(401);
        }
    }

    @Test
    void aConsumerDecoderAndConverterReplaceThePlatforms() {
        try (ConfigurableApplicationContext app = start(ConsumerDecoderApp.class)) {
            assertThat(app.getBean(JwtDecoder.class)).isSameAs(ConsumerDecoderApp.DECODER);
            assertThat(app.getBean(JwtAuthenticationConverter.class)).isSameAs(ConsumerDecoderApp.CONVERTER);
        }
    }

    @Test
    void aCustomizerCannotOpenEverything() {
        assertThatThrownBy(() -> start(WideOpenCustomizerApp.class, valid()).close()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> start(WildcardCustomizerApp.class, valid()).close())
                .hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class)
                .rootCause().hasMessageContaining("opens every path");
    }

    @Test
    void aWildcardForOneMethodIsCaughtToo() {
        assertThatThrownBy(() -> start(PostWildcardApp.class, valid()).close())
                .hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class)
                .rootCause().hasMessageContaining("anonymous POST");
    }

    @Test
    void aMatcherThatFailsOnTheCheckFailsStartup() {
        assertThatThrownBy(() -> start(ThrowingMatcherApp.class, valid()).close())
                .hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class)
                .rootCause().hasMessageContaining("failed on a check request");
    }

    @Test
    void aMatcherReadingTheServerNameIsAnswered() throws Exception {
        try (ConfigurableApplicationContext app = start(ServerNameMatcherApp.class, valid())) {
            assertThat(status(app, "/closed")).isEqualTo(401);
        }
    }

    @Test
    void theCheckCanBeSkippedExplicitly() throws Exception {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add("dc.platform.security.skip-open-path-check=true");
        start(ThrowingMatcherApp.class, props.toArray(String[]::new)).close();
    }

    @Test
    void aSingleSegmentPermitIsNotMistakenForEverything() throws Exception {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add("dc.platform.security.permit-paths=/*,/*/*");
        try (ConfigurableApplicationContext app = start(App.class, props.toArray(String[]::new))) {
            assertThat(status(app, "/open")).isEqualTo(200);
        }
    }

    @Test
    void permitPathsCannotOpenEverything() {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add("dc.platform.security.permit-paths=/**");
        assertRefused("opens every path", props.toArray(String[]::new));
    }

    @Test
    void anUnrelatedConfigurationStaysOpenToItsNamedPathsOnly() throws Exception {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add("dc.platform.security.permit-paths=/open");
        try (ConfigurableApplicationContext app = start(App.class, props.toArray(String[]::new))) {
            assertThat(status(app, "/open")).isEqualTo(200);
            assertThat(status(app, "/closed")).isEqualTo(401);
        }
    }

    @Test
    void theStrictDecoderIsRegisteredAndBootsNameIsTheRealOne() throws Exception {
        Class.forName("org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration");
        try (ConfigurableApplicationContext app = start(App.class, valid())) {
            assertThat(app.getBeanNamesForType(JwtDecoder.class)).containsExactly("jwtDecoder");
        }
    }

    @Test
    void aHigherSourceReplacesAWholeList() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty(JWT + "issuer-uri", "https://idp.test/");
        env.setProperty(JWT + "jwk-set-uri", "https://idp.test/jwks");
        env.setProperty(JWT + "audiences[0]", "legacy");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("override",
                java.util.Map.of(JWT + "audiences", "production")));
        org.springframework.boot.context.properties.bind.Binder binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        assertThat(binder.bind(JWT + "audiences", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).get())
                .containsExactly("production");
    }

    @ParameterizedTest
    @CsvSource({"connect-timeout", "read-timeout", "cache-ttl", "refresh-cooldown", "outage-tolerance"})
    void aZeroDurationIsRefused(String key) {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add("dc.platform.security.jwt.jwks." + key + "=0s");
        assertRefused(key, props.toArray(String[]::new));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "issuer-uri | issuer-uri",
        "jwk-set-uri | jwk-set-uri",
        "audiences | audiences",
    })
    void eachRequiredKeyIsNamedWhenMissing(String left, String named) {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.removeIf(p -> p.startsWith(JWT + left));
        assertRefused(named, props.toArray(String[]::new));
    }

    @ParameterizedTest
    @CsvSource({"none", "HS256", "HS512"})
    void aSharedSecretOrNoSignatureIsNotAllowed(String algorithm) {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add(JWT + "jws-algorithms=" + algorithm);
        assertRefused(algorithm, props.toArray(String[]::new));
    }

    @Test
    void anExcessiveSkewIsNotAllowed() {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.add("dc.platform.security.jwt.clock-skew=6m");
        assertRefused("clock-skew", props.toArray(String[]::new));
    }

    @Test
    void aPlainHttpJwksUrlIsNotAllowedUnlessAskedFor() throws Exception {
        List<String> props = new ArrayList<>(List.of(valid()));
        props.removeIf(p -> p.startsWith(JWT + "jwk-set-uri"));
        props.add(JWT + "jwk-set-uri=http://idp.internal/jwks");
        assertRefused("https", props.toArray(String[]::new));
        props.add("dc.platform.security.jwt.allow-insecure-jwks=true");
        start(App.class, props.toArray(String[]::new)).close();
    }

    @Test
    void theDefaultsAreWhatTheDocumentationSays() {
        try (ConfigurableApplicationContext app = start(App.class, valid())) {
            SecurityProperties properties = app.getBean(SecurityProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.mode()).isEqualTo(SecurityProperties.Mode.RESOURCE_SERVER);
            assertThat(properties.permitPaths()).containsExactly("/actuator/health/liveness", "/actuator/health/readiness");
            assertThat(properties.allowPartialChains()).isFalse();
            assertThat(properties.jwt().clockSkew()).hasSeconds(30);
            assertThat(properties.jwt().allowInsecureJwks()).isFalse();
            assertThat(properties.jwt().resourceClient()).isEmpty();
            assertThat(properties.jwt().jwks().connectTimeout()).hasSeconds(2);
            assertThat(properties.jwt().jwks().readTimeout()).hasSeconds(2);
            assertThat(properties.jwt().jwks().cacheTtl()).hasMinutes(5);
            assertThat(properties.jwt().jwks().refreshCooldown()).hasSeconds(30);
            assertThat(properties.jwt().jwks().outageTolerance()).hasHours(1);
            assertThat(app.getBean(CurrentUserAccessor.class)).isNotNull();
        }
    }

    private static void assertRefused(String named, String... props) {
        assertThatThrownBy(() -> start(App.class, props).close())
                .hasRootCauseInstanceOf(InvalidSecurityConfigurationException.class)
                .rootCause().hasMessageContaining(named);
    }

    private static String[] valid() {
        return new String[] {JWT + "issuer-uri=https://idp.test/", JWT + "jwk-set-uri=https://idp.test/jwks", JWT + "audiences=svc"};
    }

    private static ConfigurableApplicationContext start(Class<?> app, String... props) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "logging.level.root=OFF"));
        all.addAll(List.of(props));
        return new SpringApplicationBuilder(app).properties(all.toArray(String[]::new)).run();
    }

    private static int status(ConfigurableApplicationContext app, String path) throws Exception {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @RestController
    static class Api {

        @GetMapping({"/open", "/closed"})
        String reached() {
            return "reached";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class App {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class NarrowChainApp {

        @Bean
        SecurityFilterChain narrow(HttpSecurity http) throws Exception {
            return http.securityMatcher("/admin/**").authorizeHttpRequests(a -> a.anyRequest().authenticated()).build();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class FullChainApp {

        @Bean
        SecurityFilterChain mine(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.requestMatchers("/open").permitAll().anyRequest().authenticated()).build();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class TwoChainsApp {

        @Bean
        @Order(1)
        SecurityFilterChain open(HttpSecurity http) throws Exception {
            return http.securityMatcher("/open").authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        @Bean
        @Order(2)
        SecurityFilterChain rest(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().authenticated()).build();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class ConsumerDecoderApp {

        static final JwtDecoder DECODER = token -> {
            throw new UnsupportedOperationException();
        };
        static final JwtAuthenticationConverter CONVERTER = new JwtAuthenticationConverter();

        @Bean
        JwtDecoder mine() {
            return DECODER;
        }

        @Bean
        JwtAuthenticationConverter myConverter() {
            return CONVERTER;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class PostWildcardApp {

        @Bean
        SecurityCustomizer postWildcard() {
            return http -> http.authorizeHttpRequests(a -> a.requestMatchers(org.springframework.http.HttpMethod.POST, "/**").permitAll());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class ServerNameMatcherApp {

        @Bean
        SecurityCustomizer serverName() {
            return http -> http.authorizeHttpRequests(a -> a.requestMatchers(r -> r.getServerName().equals("blocked.example")).denyAll());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class ThrowingMatcherApp {

        @Bean
        SecurityCustomizer throwing() {
            return http -> http.authorizeHttpRequests(a -> a.requestMatchers(request -> {
                throw new IllegalStateException("cannot match");
            }).authenticated());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class WildcardCustomizerApp {

        @Bean
        SecurityCustomizer wildcard() {
            return http -> http.authorizeHttpRequests(a -> a.requestMatchers("/**").permitAll());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class WideOpenCustomizerApp {

        @Bean
        SecurityCustomizer wideOpen() {
            return http -> http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        }
    }
}
