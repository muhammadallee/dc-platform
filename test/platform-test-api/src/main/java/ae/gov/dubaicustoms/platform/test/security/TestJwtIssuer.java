package ae.gov.dubaicustoms.platform.test.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.apiguardian.api.API;

/**
 * A loopback JWT issuer for tests that must exercise <em>real</em> bearer-token validation over HTTP,
 * which {@link TestTokens} (a {@code MockMvc} post-processor that bypasses the decoder) cannot do. It
 * generates an RSA key pair, serves its public half as a JWK Set on {@code 127.0.0.1} at an ephemeral
 * port, and mints RS256-signed tokens — no identity provider, network, Docker, or credentials.
 *
 * <pre>{@code
 * static final TestJwtIssuer ISSUER = TestJwtIssuer.start();
 *
 * @DynamicPropertySource
 * static void issuer(DynamicPropertyRegistry registry) {
 *     registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
 * }
 *
 * // then, over real HTTP:  Authorization: Bearer <ISSUER.token("alice")>
 * }</pre>
 *
 * <p>{@link #untrustedToken(String)} and {@link #expiredToken(String)} produce well-formed tokens a
 * correctly configured resource server must reject. Test-only: the key material lives in memory for
 * the lifetime of the instance and is never suitable for anything else.
 *
 * <p>Thread-safe; tokens can be minted concurrently. Arguments are never {@code null}. Close the
 * issuer (try-with-resources or {@code @AfterAll}) to release the port.
 *
 * @since 1.0.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "1.0.0")
public final class TestJwtIssuer implements AutoCloseable {

    private static final String JWKS_PATH = "/.well-known/jwks.json";
    private static final String KEY_ID = "platform-test-key";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final HttpServer server;
    private final KeyPair trusted;
    private final KeyPair untrusted;

    private TestJwtIssuer(HttpServer server, KeyPair trusted, KeyPair untrusted) {
        this.server = server;
        this.trusted = trusted;
        this.untrusted = untrusted;
    }

    /**
     * Generates fresh keys and starts serving the JWK Set on a loopback ephemeral port.
     *
     * @return a running issuer; never {@code null}
     * @throws IllegalStateException if the key pair cannot be generated or the port cannot be bound
     */
    public static TestJwtIssuer start() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            TestJwtIssuer issuer = new TestJwtIssuer(server, generator.generateKeyPair(), generator.generateKeyPair());
            server.createContext(JWKS_PATH, issuer::serveJwks);
            server.start();
            return issuer;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("could not start the test JWT issuer", e);
        }
    }

    /**
     * Returns the issuer base URI (also the {@code iss} claim of every minted token).
     *
     * @return {@code http://127.0.0.1:<port>}; never {@code null}
     */
    public String issuerUri() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /**
     * Returns the JWK Set URI to configure as
     * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}.
     *
     * @return the loopback JWK Set URI; never {@code null}
     */
    public String jwkSetUri() {
        return issuerUri() + JWKS_PATH;
    }

    /**
     * Mints a valid token for {@code subject} that expires in five minutes.
     *
     * @param subject the {@code sub} claim (also {@code preferred_username}); never {@code null}
     * @return the compact serialized JWT; never {@code null}
     */
    public String token(String subject) {
        return token(subject, Map.of(), Duration.ofMinutes(5));
    }

    /**
     * Mints a token with additional claims and the given lifetime; a negative lifetime yields an
     * already-expired token. Claim values may be strings, numbers, booleans, or collections of those.
     *
     * @param subject the {@code sub} claim; never {@code null}
     * @param claims additional claims; never {@code null}, may be empty
     * @param timeToLive lifetime from now; never {@code null}
     * @return the compact serialized JWT; never {@code null}
     * @throws IllegalArgumentException if a claim value has an unsupported type
     */
    public String token(String subject, Map<String, ?> claims, Duration timeToLive) {
        return sign(trusted.getPrivate(), subject, claims, timeToLive);
    }

    /**
     * Mints a token whose lifetime ended ten minutes ago — beyond any default clock-skew allowance.
     *
     * @param subject the {@code sub} claim; never {@code null}
     * @return the compact serialized JWT; never {@code null}
     */
    public String expiredToken(String subject) {
        return token(subject, Map.of(), Duration.ofMinutes(-10));
    }

    /**
     * Mints an otherwise valid token signed by a key that is NOT in the served JWK Set (same key id),
     * so signature verification must fail.
     *
     * @param subject the {@code sub} claim; never {@code null}
     * @return the compact serialized JWT; never {@code null}
     */
    public String untrustedToken(String subject) {
        return sign(untrusted.getPrivate(), subject, Map.of(), Duration.ofMinutes(5));
    }

    /** Stops serving the JWK Set and releases the port. */
    @Override
    public void close() {
        server.stop(0);
    }

    private String sign(PrivateKey key, String subject, Map<String, ?> claims, Duration timeToLive) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(claims, "claims must not be null");
        Objects.requireNonNull(timeToLive, "timeToLive must not be null");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(timeToLive);
        // iat must not follow exp, or an expired token would also carry a future issued-at time.
        Instant issuedAt = expiresAt.isBefore(now) ? expiresAt.minus(Duration.ofMinutes(5)) : now;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuerUri());
        payload.put("sub", subject);
        payload.put("preferred_username", subject);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.putAll(claims);
        String header = json(Map.of("alg", "RS256", "typ", "JWT", "kid", KEY_ID));
        String signingInput = encode(header) + "." + encode(json(payload));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
    }

    private void serveJwks(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            RSAPublicKey key = (RSAPublicKey) trusted.getPublic();
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("kid", KEY_ID);
            jwk.put("use", "sig");
            jwk.put("alg", "RS256");
            jwk.put("n", unsigned(key.getModulus()));
            jwk.put("e", unsigned(key.getPublicExponent()));
            byte[] body = ("{\"keys\":[" + json(jwk) + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        // Two's-complement adds a leading zero byte for positive values with the top bit set; JWK wants
        // the unsigned big-endian magnitude.
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return BASE64URL.encodeToString(bytes);
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(Map<String, ?> object) {
        StringJoiner members = new StringJoiner(",", "{", "}");
        object.forEach((name, value) -> members.add(quote(name) + ":" + jsonValue(value)));
        return members.toString();
    }

    private static String jsonValue(Object value) {
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Collection<?> values) {
            StringJoiner elements = new StringJoiner(",", "[", "]");
            values.forEach(element -> elements.add(jsonValue(element)));
            return elements.toString();
        }
        throw new IllegalArgumentException("unsupported claim value type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
