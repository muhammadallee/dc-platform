package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.security.autoconfigure.SecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the platform's {@link JwtDecoder}: a fixed issuer, audience, algorithm list and skew, and a JWKS source that is
 * bounded, cached and rate limited. Nothing is contacted at startup, and a setup that would accept tokens it should not
 * fails startup instead.
 */
public final class PlatformJwtDecoderFactory {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtDecoderFactory.class);
    private static final String PREFIX = "spring.security.oauth2.resourceserver.jwt.";
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private static final int MAX_JWKS_BYTES = 1024 * 1024;
    private static final int MIN_RSA_BITS = 2048;
    private static final Set<JWSAlgorithm> ASYMMETRIC = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);

    private PlatformJwtDecoderFactory() {
    }

    /**
     * Builds the decoder from Spring's resource-server keys and the platform's settings.
     *
     * @param environment holds {@code spring.security.oauth2.resourceserver.jwt.*}
     * @param settings the platform's settings
     * @param metrics receives one count per fetch
     * @return the decoder; never {@code null}
     * @throws InvalidSecurityConfigurationException if the setup is refused
     */
    public static JwtDecoder create(Environment environment, SecurityProperties.Jwt settings, SecurityMetrics metrics) {
        Binder binder = Binder.get(environment);
        String issuer = required(binder, "issuer-uri");
        String jwkSetUri = required(binder, "jwk-set-uri");
        List<String> audiences = list(binder, "audiences");
        if (audiences.isEmpty()) {
            throw missing("audiences");
        }
        Set<JWSAlgorithm> algorithms = algorithms(list(binder, "jws-algorithms"));
        positive("jwks.connect-timeout", settings.jwks().connectTimeout());
        positive("jwks.read-timeout", settings.jwks().readTimeout());
        positive("jwks.cache-ttl", settings.jwks().cacheTtl());
        positive("jwks.refresh-cooldown", settings.jwks().refreshCooldown());
        positive("jwks.outage-tolerance", settings.jwks().outageTolerance());
        Duration skew = settings.clockSkew();
        if (skew.isNegative() || skew.compareTo(MAX_SKEW) > 0) {
            throw new InvalidSecurityConfigurationException("dc.platform.security.jwt.clock-skew is " + skew
                    + ", and at most " + MAX_SKEW + " is allowed.", "Set a smaller clock skew.");
        }
        URL jwks = jwksUrl(jwkSetUri, settings.allowInsecureJwks());

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, jwkSource(jwks, settings.jwks(), metrics)));
        // Spring validates claims and accepts any typ, such as at+jwt.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        processor.setJWSTypeVerifier((type, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(issuer, audiences, skew));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> validator(String issuer, List<String> audiences, Duration skew) {
        return new DelegatingOAuth2TokenValidator<>(List.<OAuth2TokenValidator<Jwt>>of(
                new JwtTimestampValidator(skew),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<Object>(JwtClaimNames.AUD, aud -> aud instanceof Collection<?> values
                        && values.stream().anyMatch(audiences::contains)),
                // The timestamp validator passes a token with no expiry, and CurrentUser needs a subject.
                new JwtClaimValidator<Object>(JwtClaimNames.EXP, exp -> exp != null),
                new JwtClaimValidator<Object>(JwtClaimNames.SUB, sub -> sub instanceof String text && !text.isBlank())));
    }

    private static JWKSource<SecurityContext> jwkSource(URL url, SecurityProperties.Jwks settings, SecurityMetrics metrics) {
        DefaultResourceRetriever http = new NoRedirects(millis(settings.connectTimeout()), millis(settings.readTimeout()));
        Duration budget = settings.connectTimeout().plus(settings.readTimeout());
        CountingRetriever retriever = new CountingRetriever(http, metrics, budget);
        JWKSource<SecurityContext> source = JWKSourceBuilder.<SecurityContext>create(url, retriever)
                .cache(settings.cacheTtl().toMillis(), settings.connectTimeout().plus(settings.readTimeout()).toMillis())
                .refreshAheadCache(false)
                .rateLimited(settings.refreshCooldown().toMillis())
                // Nimbus counts this from the last good fetch, and the promise is outage-tolerance beyond cache-ttl.
                .outageTolerant(settings.cacheTtl().plus(settings.outageTolerance()).toMillis())
                .retrying(false)
                .build();
        return new RateLimitAsNoKey(source, retriever);
    }

    private static int millis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private static void positive(String key, Duration value) {
        if (value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE) {
            throw new InvalidSecurityConfigurationException("dc.platform.security.jwt." + key + " is " + value
                    + ", which is out of range: a fetch timeout of zero would mean no timeout at all.",
                    "Set a duration of at least 1 ms and under 24 days.");
        }
    }

    private static String required(Binder binder, String key) {
        String value = binder.bind(PREFIX + key, String.class).orElse("");
        if (value.isBlank()) {
            throw missing(key);
        }
        return value;
    }

    private static InvalidSecurityConfigurationException missing(String key) {
        return new InvalidSecurityConfigurationException(PREFIX + key + " is not set, and the platform will not accept tokens "
                + "without checking their issuer, signing keys and audience.",
                "Set " + PREFIX + "issuer-uri, jwk-set-uri and audiences, supply your own JwtDecoder bean, "
                        + "or set dc.platform.security.mode=disabled to leave security to the application.");
    }

    // Boot's binder, so a higher-precedence source replaces a whole list, as everywhere else in Spring.
    private static List<String> list(Binder binder, String key) {
        return binder.bind(PREFIX + key, Bindable.listOf(String.class)).orElse(List.of()).stream()
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    private static Set<JWSAlgorithm> algorithms(List<String> configured) {
        if (configured.isEmpty()) {
            return Set.of(JWSAlgorithm.RS256);
        }
        Set<JWSAlgorithm> algorithms = new LinkedHashSet<>();
        for (String name : configured) {
            JWSAlgorithm algorithm = JWSAlgorithm.parse(name.toUpperCase(Locale.ROOT));
            if (!ASYMMETRIC.contains(algorithm)) {
                throw new InvalidSecurityConfigurationException(PREFIX + "jws-algorithms lists " + name
                        + ", which a resource server must not accept: a shared secret or no signature at all.",
                        "List only asymmetric algorithms, such as RS256 or ES256.");
            }
            algorithms.add(algorithm);
        }
        return algorithms;
    }

    private static URL jwksUrl(String jwkSetUri, boolean allowInsecure) {
        URI uri;
        URL url;
        try {
            uri = URI.create(jwkSetUri);
            url = uri.toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new InvalidSecurityConfigurationException(PREFIX + "jwk-set-uri is not a valid URL.", "Correct the URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !allowInsecure && !loopback(uri.getHost())) {
            throw new InvalidSecurityConfigurationException(PREFIX + "jwk-set-uri does not use https, so anyone on the path could "
                    + "supply signing keys and forge tokens.",
                    "Use https, or set dc.platform.security.jwt.allow-insecure-jwks=true where the network is trusted.");
        }
        return url;
    }

    // Literal addresses and localhost only, so this never looks up a name.
    private static boolean loopback(String host) {
        if (host == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        if (!host.matches("[0-9.]+") && !host.startsWith("[")) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** A redirect would skip the check on the URL, so one is a failed fetch. */
    private static final class NoRedirects extends DefaultResourceRetriever {

        NoRedirects(int connectTimeout, int readTimeout) {
            super(connectTimeout, readTimeout, MAX_JWKS_BYTES);
        }

        @Override
        protected HttpURLConnection openHTTPConnection(URL url) throws IOException {
            HttpURLConnection connection = super.openHTTPConnection(url);
            connection.setInstanceFollowRedirects(false);
            return connection;
        }
    }

    /**
     * Bounds a whole fetch, since the read timeout resets on every byte; a fetch counts as good only if it holds keys; counts
     * fetches, remembers how the last one went, and logs a failure without the URL's query or the cause's text.
     */
    private static final class CountingRetriever implements ResourceRetriever {

        private final ResourceRetriever delegate;
        private final SecurityMetrics metrics;
        private final Duration budget;
        private volatile boolean lastFailed;

        CountingRetriever(ResourceRetriever delegate, SecurityMetrics metrics, Duration budget) {
            this.delegate = delegate;
            this.metrics = metrics;
            this.budget = budget;
        }

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            try {
                Resource resource = bounded(url);
                requireKeys(resource);
                lastFailed = false;
                count(true);
                return resource;
            } catch (IOException | RuntimeException e) {
                lastFailed = true;
                count(false);
                log.warn("could not fetch the JWKS from {}://{}:{}: {}", url.getProtocol(), url.getHost(),
                        url.getPort() < 0 ? url.getDefaultPort() : url.getPort(), e.getClass().getSimpleName());
                throw e;
            }
        }

        private Resource bounded(URL url) throws IOException {
            FutureTask<Resource> fetch = new FutureTask<>(() -> delegate.retrieveResource(url));
            Thread.ofVirtual().name("platform-jwks-fetch").start(fetch);
            try {
                return fetch.get(budget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fetch.cancel(true);
                throw new IOException("the fetch took too long");
            } catch (InterruptedException e) {
                fetch.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw new IOException("the fetch failed");
            }
        }

        private static void requireKeys(Resource resource) throws IOException {
            try {
                if (JWKSet.parse(resource.getContent()).isEmpty()) {
                    throw new IOException("no keys in the response");
                }
            } catch (ParseException | RuntimeException e) {
                throw new IOException("not a JWK set");
            }
        }

        private void count(boolean success) {
            try {
                metrics.jwksFetch(success);
            } catch (RuntimeException e) {
                log.debug("JWKS metric failed", e);
            }
        }
    }

    /**
     * An unknown key id is a plain rejection, within the cooldown too, unless the last fetch failed: then the key may well exist,
     * for instance after a rotation, and it is an outage. Outage tolerance answers with the old keys, so an empty result counts too.
     */
    private static final class RateLimitAsNoKey implements JWKSource<SecurityContext> {

        private final JWKSource<SecurityContext> delegate;
        private final CountingRetriever retriever;

        RateLimitAsNoKey(JWKSource<SecurityContext> delegate, CountingRetriever retriever) {
            this.delegate = delegate;
            this.retriever = retriever;
        }

        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
            List<JWK> keys;
            try {
                keys = delegate.get(selector, context);
            } catch (RateLimitReachedException e) {
                keys = List.of();
            }
            if (keys.isEmpty() && retriever.lastFailed) {
                throw new RemoteKeySourceException("the signing keys cannot be fetched", null);
            }
            return keys.stream()
                    .filter(key -> !(key instanceof RSAKey rsa) || rsa.getModulus().decodeToBigInteger().bitLength() >= MIN_RSA_BITS)
                    .toList();
        }
    }
}
