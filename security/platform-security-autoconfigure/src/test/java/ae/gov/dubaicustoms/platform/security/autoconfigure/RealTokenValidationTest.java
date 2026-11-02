package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real server, real JWKS endpoint, real signed tokens: what the mock {@code jwt()} post-processor never reaches. */
class RealTokenValidationTest {

    private static final String ISSUER = "http://issuer.test";
    private static final String AUDIENCE = "svc";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final AtomicInteger fetches = new AtomicInteger();
    private volatile int behaviour;
    private volatile JWKSet served;
    private RSAKey key;
    private HttpServer jwks;
    private ConfigurableApplicationContext app;

    @BeforeEach
    void startJwks() throws Exception {
        key = rsa("k1");
        served = new JWKSet(key.toPublicJWK());
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.setExecutor(Executors.newCachedThreadPool());
        jwks.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            if (behaviour == 2) {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (behaviour == 1) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                byte[] body = served.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        jwks.start();
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.close();
        }
        jwks.stop(0);
    }

    @Test
    void aValidTokenReachesTheApplicationWithItsSubject() throws Exception {
        start();
        HttpResponse<String> response = get(rs256(key, claims().build()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("alice");
    }

    @Test
    void everyBadTokenGetsTheSameRefusal() throws Exception {
        start();
        JWTClaimsSet good = claims().build();
        byte[] secret = MessageDigest.getInstance("SHA-256").digest(key.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8));
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), good);
        hmac.sign(new MACSigner(secret));
        SignedJWT rs512 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID("k1").build(), good);
        rs512.sign(new RSASSASigner(key));

        List<String> tokens = new ArrayList<>(List.of(
                rs256(key, claims().issuer("http://evil").build()),
                rs256(key, claims().issuer(null).build()),
                rs256(key, claims().audience("other").build()),
                rs256(key, claims().audience((String) null).build()),
                rs256(key, claims().expirationTime(null).build()),
                rs256(key, claims().subject(null).build()),
                rs256(key, claims().subject(" ").build()),
                rs256(key, claims().expirationTime(at(-90)).build()),
                rs256(key, claims().notBeforeTime(at(90)).build()),
                new PlainJWT(good).serialize(),
                hmac.serialize(),
                rs512.serialize(),
                rs256(rsa("k1"), good),
                rs256(rsa("unknown"), good),
                "garbage"));
        String expected = null;
        for (String token : tokens) {
            HttpResponse<String> response = get(token);
            assertThat(response.statusCode()).as(token).isEqualTo(401);
            assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
            assertThat(response.body()).doesNotContain(token);
            String shape = response.body().replaceAll("\"timestamp\":\"[^\"]*\"", "");
            if (expected == null) {
                expected = shape;
            }
            assertThat(shape).as("the reason must not show").isEqualTo(expected);
        }
        assertThat(get(null).body().replaceAll("\"timestamp\":\"[^\"]*\"", "")).isEqualTo(expected);
    }

    @Test
    void theSkewIsTheConfiguredOne() throws Exception {
        start("dc.platform.security.jwt.clock-skew=30s");
        assertThat(get(rs256(key, claims().expirationTime(at(-10)).build())).statusCode()).isEqualTo(200);
        assertThat(get(rs256(key, claims().notBeforeTime(at(10)).build())).statusCode()).isEqualTo(200);
        assertThat(get(rs256(key, claims().expirationTime(at(-45)).build())).statusCode()).isEqualTo(401);
        assertThat(get(rs256(key, claims().notBeforeTime(at(45)).build())).statusCode()).isEqualTo(401);
    }

    @Test
    void anotherListedAudienceAndAListedAlgorithmAreAccepted() throws Exception {
        start("spring.security.oauth2.resourceserver.jwt.audiences=first,svc",
                "spring.security.oauth2.resourceserver.jwt.jws-algorithms=RS256,RS512");
        SignedJWT rs512 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID("k1").build(), claims().build());
        rs512.sign(new RSASSASigner(key));
        assertThat(get(rs512.serialize()).statusCode()).isEqualTo(200);
    }

    @Test
    void unknownKeyIdsCannotHammerTheProvider() throws Exception {
        start();
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
        int before = fetches.get();
        String stranger = rs256(rsa("stranger"), claims().build());
        for (int i = 0; i < 20; i++) {
            assertThat(get(stranger).statusCode()).isEqualTo(401);
        }
        assertThat(fetches.get() - before).isLessThanOrEqualTo(2);
    }

    @Test
    void aKeyAddedAtTheProviderWorksWithoutARestart() throws Exception {
        start("dc.platform.security.jwt.jwks.refresh-cooldown=1ms");
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
        RSAKey next = rsa("k2");
        served = new JWKSet(List.of(key.toPublicJWK(), next.toPublicJWK()));
        Thread.sleep(5);
        assertThat(get(rs256(next, claims().build())).statusCode()).isEqualTo(200);
    }

    @Test
    void aKeyRemovedAtTheProviderStopsWorkingAfterTheCacheTtl() throws Exception {
        start("dc.platform.security.jwt.jwks.cache-ttl=200ms", "dc.platform.security.jwt.jwks.refresh-cooldown=1ms");
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
        served = new JWKSet(rsa("k2").toPublicJWK());
        Thread.sleep(300);
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(401);
    }

    @Test
    void anOutageKeepsKnownKeysWorkingAndAFreshKeyGetsARetryable503() throws Exception {
        start("dc.platform.security.jwt.jwks.cache-ttl=100ms", "dc.platform.security.jwt.jwks.refresh-cooldown=1ms");
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
        behaviour = 1;
        Thread.sleep(150);
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
        HttpResponse<String> fresh = get(rs256(rsa("fresh"), claims().build()));
        assertUnavailable(fresh);
        behaviour = 0;
        Thread.sleep(150);
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
    }

    @Test
    void beyondTheOutageToleranceCachedKeysStopWorking() throws Exception {
        start("dc.platform.security.jwt.jwks.cache-ttl=100ms", "dc.platform.security.jwt.jwks.refresh-cooldown=1ms",
                "dc.platform.security.jwt.jwks.outage-tolerance=100ms");
        assertThat(get(rs256(key, claims().build())).statusCode()).isEqualTo(200);
        behaviour = 1;
        Thread.sleep(300);
        assertUnavailable(get(rs256(key, claims().build())));
    }

    @Test
    void aColdStartDuringAnOutageIsA503AndKeepsBeingOneWithinTheCooldown() throws Exception {
        behaviour = 1;
        start();
        assertUnavailable(get(rs256(key, claims().build())));
        assertUnavailable(get(rs256(key, claims().build())));
        assertUnavailable(get(rs256(key, claims().build())));
    }

    @Test
    void aHungProviderIsAnswerWithinTheFetchTimeouts() throws Exception {
        behaviour = 2;
        start("dc.platform.security.jwt.jwks.read-timeout=500ms");
        long started = System.nanoTime();
        assertUnavailable(get(rs256(key, claims().build())));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aProviderDrippingBytesIsCutOffAtTheBudget() throws Exception {
        jwks.createContext("/drip", exchange -> {
            fetches.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 300; i++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(100);
                }
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        start("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/drip",
                "dc.platform.security.jwt.jwks.read-timeout=500ms", "dc.platform.security.jwt.jwks.connect-timeout=500ms");
        long started = System.nanoTime();
        assertUnavailable(get(rs256(key, claims().build())));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aBadResponseCountsAsAnOutageNotAsNoSuchKey() throws Exception {
        jwks.createContext("/html", exchange -> {
            byte[] page = "<html>sign in</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        start("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/html");
        assertUnavailable(get(rs256(key, claims().build())));
        assertUnavailable(get(rs256(key, claims().build())));
    }

    @Test
    void aRedirectIsNotFollowed() throws Exception {
        jwks.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        start("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/moved");
        assertUnavailable(get(rs256(key, claims().build())));
        assertThat(fetches.get()).isZero();
    }

    @Test
    void aShortRsaKeyIsRefused() throws Exception {
        RSAKey weak = new RSAKeyGenerator(1024, true).keyID("weak").keyUse(KeyUse.SIGNATURE).generate();
        served = new JWKSet(weak.toPublicJWK());
        start();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("weak").build(), claims().build());
        jwt.sign(new RSASSASigner(weak.toPrivateKey(), true));
        assertThat(get(jwt.serialize()).statusCode()).isEqualTo(401);
    }

    @Test
    void scopesOfTheWrongShapeGrantNothingAndAreNotAFailure() throws Exception {
        start();
        assertThat(get(rs256(key, claims().claim("scp", List.of(123)).build())).statusCode()).isEqualTo(200);
    }

    @Test
    void nothingIsFetchedAtStartup() {
        start();
        assertThat(fetches.get()).isZero();
    }

    private static void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"DC-SEC-0503\"").contains("\"retryable\":true")
                .contains("Authentication is temporarily unavailable").doesNotContain("jwks").doesNotContain("127.0.0.1");
    }

    private void start(String... extra) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "logging.level.root=WARN",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE));
        all.addAll(List.of(extra));
        app = new SpringApplicationBuilder(App.class).properties(all.toArray(String[]::new)).run();
    }

    private HttpResponse<String> get(String token) throws IOException, InterruptedException {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/whoami")).timeout(Duration.ofSeconds(20));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static RSAKey rsa(String id) throws Exception {
        return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).generate();
    }

    private static Date at(int seconds) {
        return Date.from(Instant.now().plusSeconds(seconds));
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().subject("alice").issuer(ISSUER).audience(AUDIENCE).expirationTime(at(300));
    }

    private static String rs256(RSAKey signer, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    @RestController
    static class Api {

        private final CurrentUserAccessor users;

        Api(CurrentUserAccessor users) {
            this.users = users;
        }

        @GetMapping("/whoami")
        String whoami() {
            return users.currentUser().orElseThrow().subject();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class App {
    }
}
