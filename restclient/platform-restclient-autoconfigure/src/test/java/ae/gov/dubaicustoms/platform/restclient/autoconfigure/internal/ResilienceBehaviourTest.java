package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException.Kind;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties.Bulkhead;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties.CircuitBreaker;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties.Defaults;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties.Retry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class ResilienceBehaviourTest {

    private static final Retry FAST = new Retry(null, null, Duration.ofMillis(1), null, null, null);
    private static final CircuitBreaker NO_BREAKER = new CircuitBreaker(false, null, null, null, null, null);

    private final MockWebServer server = new MockWebServer();
    private final List<DefaultPlatformRestClientFactory> factories = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        factories.forEach(DefaultPlatformRestClientFactory::destroy);
        server.shutdown();
    }

    private DefaultPlatformRestClientFactory factory(Defaults defaults) {
        DefaultPlatformRestClientFactory factory = new DefaultPlatformRestClientFactory(new RestClientProperties(true, defaults, Map.of()),
                List.of());
        factories.add(factory);
        return factory;
    }

    private RestClient client(Defaults defaults) {
        return factory(defaults).builder("orders").baseUrl(server.url("/").toString()).build();
    }

    private RestClient fastClient() {
        return client(new Defaults(null, Duration.ofSeconds(2), null, FAST, NO_BREAKER, null));
    }

    private static MockResponse status(int code) {
        return new MockResponse().setResponseCode(code);
    }

    private static RemoteCallException failure(Runnable call) {
        try {
            call.run();
        } catch (RemoteCallException e) {
            return e;
        }
        throw new AssertionError("expected a RemoteCallException");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 504})
    void anIdempotentCallIsRetriedOnATransientStatusUntilExhausted(int code) {
        for (int i = 0; i < 3; i++) {
            server.enqueue(status(code));
        }
        RestClient client = fastClient();

        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(e.kind()).isEqualTo(Kind.RESPONSE);
        assertThat(e.status()).isEqualTo(code);
        assertThat(e.problemStatus()).isEqualTo(503);
        assertThat(e.retryable()).isTrue();
    }

    @Test
    void aRetrySucceedsWhenTheServerRecovers() {
        server.enqueue(status(503));
        server.enqueue(new MockResponse().setBody("ok"));
        RestClient client = fastClient();

        assertThat(client.get().uri("/a").retrieve().body(String.class)).isEqualTo("ok");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422, 500})
    void otherFailuresAreNotRetried(int code) {
        server.enqueue(status(code));
        RestClient client = fastClient();

        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(e.problemStatus()).isEqualTo(500);
        assertThat(e.retryable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void aNonIdempotentMethodIsSentExactlyOnceOnATransientStatus(String method) throws Exception {
        server.enqueue(status(503));
        server.enqueue(new MockResponse());
        RestClient client = fastClient();

        RemoteCallException e = failure(() -> client.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/a")
                .header("Idempotency-Key", "key-1").body("x").retrieve().toBodilessEntity());

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getHeader("Idempotency-Key")).isEqualTo("key-1");
        assertThat(e.problemStatus()).isEqualTo(500);
        assertThat(e.retryable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void aNonIdempotentMethodIsSentExactlyOnceWhenTheConnectionDropsAfterSending(String method) {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        }
        RestClient client = fastClient();

        RemoteCallException e = failure(() -> client.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/a")
                .header("Idempotency-Key", "key-1").body("x").retrieve().toBodilessEntity());

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    void aPostTimeoutIsNeitherRetriedNorCalledRetryable() {
        server.enqueue(new MockResponse().setHeadersDelay(3, TimeUnit.SECONDS));
        RestClient client = client(new Defaults(null, Duration.ofMillis(200), null, FAST, NO_BREAKER, null));

        RemoteCallException e = failure(() -> client.post().uri("/a").body("x").retrieve().toBodilessEntity());

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    void aConnectionNeverMadeIsSafeToRepeatEvenForAPost() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        RestClient client = factory(new Defaults(null, null, null, FAST, NO_BREAKER, null)).builder("x")
                .baseUrl("http://127.0.0.1:" + port).build();

        RemoteCallException e = failure(() -> client.post().uri("/a").body("x").retrieve().toBodilessEntity());

        assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
        assertThat(e.problemStatus()).isEqualTo(503);
        assertThat(e.status()).isZero();
    }

    @Test
    void aGetIsRetriedAfterADroppedConnectionWithinABudgetTheJdkDoubles() {
        for (int i = 0; i < 8; i++) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        }
        RestClient client = fastClient();

        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(e.kind()).isEqualTo(Kind.CONNECTION);
        assertThat(e.problemStatus()).isEqualTo(503);
        // The JDK client re-sends a GET once by itself, on each of our attempts.
        assertThat(server.getRequestCount()).isBetween(3, 6);
    }

    @Test
    void retryAfterInSecondsReplacesTheBackoff() {
        server.enqueue(status(503).addHeader("Retry-After", "1"));
        server.enqueue(new MockResponse());
        RestClient client = fastClient();

        long started = System.nanoTime();
        client.get().uri("/a").retrieve().toBodilessEntity();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(950));
    }

    @Test
    void retryAfterAsADateIsUnderstood() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60)));

        assertThat(ResilientRequestFactory.retryAfter(headers)).get()
                .satisfies(wait -> assertThat(wait).isBetween(Duration.ofSeconds(55), Duration.ofSeconds(60)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "", "-5"})
    void aNonsenseRetryAfterIsHarmless(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", value);

        assertThat(ResilientRequestFactory.retryAfter(headers).orElse(Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void aRetryAfterBeyondTheDeadlineFailsAtOnce() {
        server.enqueue(status(503).addHeader("Retry-After", "60"));
        RestClient client = client(new Defaults(null, null, Duration.ofSeconds(5), FAST, NO_BREAKER, null));

        long started = System.nanoTime();
        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(e.status()).isEqualTo(503);
    }

    @Test
    void theDeadlineBoundsAllAttemptsAndIsReported() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));
        }
        RestClient client = client(new Defaults(null, Duration.ofSeconds(10), Duration.ofMillis(400), FAST, NO_BREAKER, null));

        long started = System.nanoTime();
        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(e.kind()).isEqualTo(Kind.DEADLINE);
        assertThat(e.problemStatus()).isEqualTo(504);
    }

    @Test
    void aReadTimeoutIsRetriedWithinTheDeadline() {
        server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("ok"));
        RestClient client = client(new Defaults(null, Duration.ofMillis(200), null, FAST, NO_BREAKER, null));

        assertThat(client.get().uri("/a").retrieve().body(String.class)).isEqualTo("ok");
    }

    @Test
    void aFailureReadingASuccessBodyIsTheOneFailureType() {
        server.enqueue(new MockResponse().setBody("y".repeat(60)).throttleBody(10, 1, TimeUnit.SECONDS));
        RestClient client = client(new Defaults(null, Duration.ofMillis(500), null, FAST, NO_BREAKER, null));

        assertThatThrownBy(() -> client.get().uri("/a").retrieve().body(String.class)).isInstanceOf(RemoteCallException.class);
    }

    @Test
    void aLargeErrorBodyIsNotReadIntoMemory() {
        server.enqueue(status(500).setBody("x".repeat(32 * 1024 * 1024)));
        RestClient client = fastClient();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();

        long before = threads.getThreadAllocatedBytes(thread);
        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(threads.getThreadAllocatedBytes(thread) - before).isLessThan(8L * 1024 * 1024);
        assertThat(e.bodySnippet()).hasSize(1024);
    }

    @Test
    void anOpenBreakerSendsNothingAndIsNotRetriedAndRecovers() {
        Map<String, Integer> codes = new HashMap<>(Map.of("code", 500));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return status(codes.get("code"));
            }
        });
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, FAST,
                new CircuitBreaker(true, 50f, 4, 4, Duration.ofMillis(300), 1), null));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();
        var breaker = factory.circuitBreakers().circuitBreaker("rest-orders");

        for (int i = 0; i < 4; i++) {
            failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());
        }
        assertThat(breaker.getState().name()).isEqualTo("OPEN");
        int sent = server.getRequestCount();

        RemoteCallException refused = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());
        assertThat(refused.kind()).isEqualTo(Kind.CIRCUIT_OPEN);
        assertThat(refused.problemStatus()).isEqualTo(503);
        assertThat(server.getRequestCount()).isEqualTo(sent);

        codes.put("code", 200);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).ignoreExceptions().untilAsserted(() ->
                client.get().uri("/a").retrieve().toBodilessEntity());
        assertThat(breaker.getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void anUpstream4xxDoesNotOpenTheBreaker() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return status(404);
            }
        });
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, FAST,
                new CircuitBreaker(true, 50f, 4, 4, Duration.ofSeconds(30), 1), null));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();

        for (int i = 0; i < 8; i++) {
            failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());
        }

        assertThat(factory.circuitBreakers().circuitBreaker("rest-orders").getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void aFullBulkheadRefusesAtOnceAndAPermitReturnsOnClose() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                release.await(10, TimeUnit.SECONDS);
                return new MockResponse().setBody("ok");
            }
        });
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, FAST, NO_BREAKER,
                new Bulkhead(true, null, 1)));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();
        Thread holder = Thread.ofVirtual().start(() -> client.get().uri("/a").retrieve().body(String.class));
        var bulkhead = factory.bulkheads().bulkhead("rest-orders");
        org.awaitility.Awaitility.await().until(() -> bulkhead.getMetrics().getAvailableConcurrentCalls() == 0);

        long started = System.nanoTime();
        RemoteCallException refused = failure(() -> client.get().uri("/a").retrieve().body(String.class));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(refused.kind()).isEqualTo(Kind.BULKHEAD_FULL);
        assertThat(refused.problemStatus()).isEqualTo(503);
        release.countDown();
        holder.join(5000);
        org.awaitility.Awaitility.await().until(() -> bulkhead.getMetrics().getAvailableConcurrentCalls() == 1);
    }

    @Test
    void permitsAreReturnedAfterFailures() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(status(503));
        }
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, new Retry(false, null, null, null, null, null),
                NO_BREAKER, new Bulkhead(true, null, 2)));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();

        for (int i = 0; i < 4; i++) {
            failure(() -> client.post().uri("/a").body("x").retrieve().toBodilessEntity());
        }

        assertThat(factory.bulkheads().bulkhead("rest-orders").getMetrics().getAvailableConcurrentCalls()).isEqualTo(2);
    }

    @Test
    void everyAttemptCarriesWhatInterceptorsAdded() throws Exception {
        server.enqueue(status(503));
        server.enqueue(status(503));
        server.enqueue(new MockResponse());
        DefaultPlatformRestClientFactory factory = new DefaultPlatformRestClientFactory(new RestClientProperties(true,
                new Defaults(null, null, null, FAST, NO_BREAKER, null), Map.of()),
                List.of((name, builder) -> builder.requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("X-Added", "yes");
                    return execution.execute(request, body);
                })));
        factories.add(factory);

        factory.builder("orders").baseUrl(server.url("/").toString()).build().put().uri("/a").body("payload").retrieve().toBodilessEntity();

        for (int i = 0; i < 3; i++) {
            RecordedRequest request = server.takeRequest();
            assertThat(request.getHeader("X-Added")).isEqualTo("yes");
            assertThat(request.getBody().readUtf8()).isEqualTo("payload");
        }
    }

    @Test
    void attemptsAreReported() {
        server.enqueue(status(503));
        server.enqueue(new MockResponse());
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, FAST, NO_BREAKER, null));
        List<String> outcomes = new ArrayList<>();
        factory.onAttempt((client, outcome) -> outcomes.add(client + ":" + outcome));

        factory.builder("orders").baseUrl(server.url("/").toString()).build().get().uri("/a").retrieve().toBodilessEntity();

        assertThat(outcomes).containsExactly("orders:status_5xx", "orders:success");
    }

    @Test
    void configuringANonIdempotentMethodFailsWithAReason() {
        assertThatThrownBy(() -> new Retry(null, null, null, null, null, Set.of("get", "post")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry.methods").hasMessageContaining("POST");
    }

    @Test
    void aThreadPoolBulkheadFailsWithAReason() {
        assertThatThrownBy(() -> new Bulkhead(null, RestClientProperties.BulkheadType.THREAD_POOL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("THREAD_POOL");
    }

    @Test
    void aClientOverrideInheritsWhatItDoesNotSet() {
        RestClientProperties properties = new RestClientProperties(true, new Defaults(null, null, null, null, null, null),
                Map.of("x", new RestClientProperties.ClientOverride(null, null, Duration.ofSeconds(3),
                        new Retry(null, 5, null, null, null, null), null, null)));

        Defaults effective = properties.timeoutsFor("x");

        assertThat(effective.deadline()).isEqualTo(Duration.ofSeconds(3));
        assertThat(effective.retry().maxAttempts()).isEqualTo(5);
        assertThat(effective.retry().enabled()).isTrue();
        assertThat(effective.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(effective.bulkhead().maxConcurrent()).isEqualTo(25);
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 308})
    void aPostIsNotSentAgainByARedirect(int code) throws Exception {
        server.enqueue(status(code).addHeader("Location", server.url("/elsewhere").toString()));
        server.enqueue(new MockResponse());
        RestClient client = fastClient();

        client.post().uri("/a").body("x").retrieve().toBodilessEntity();

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aGetStillFollowsARedirect() {
        server.enqueue(status(307).addHeader("Location", server.url("/elsewhere").toString()));
        server.enqueue(new MockResponse().setBody("there"));
        RestClient client = fastClient();

        assertThat(client.get().uri("/a").retrieve().body(String.class)).isEqualTo("there");
    }

    @Test
    void aFailedRedirectTargetAfterAPostCannotBeCalledRepeatable() {
        server.enqueue(status(303).addHeader("Location", "http://127.0.0.1:1/created"));
        RestClient client = fastClient();

        assertThat(client.post().uri("/a").body("x").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(303);
    }

    @Test
    void aMethodNotKnownToBeIdempotentIsNotRetriedOrCalledRepeatable() {
        server.enqueue(status(503));
        server.enqueue(new MockResponse());
        RestClient client = fastClient();

        RemoteCallException e = failure(() -> client.method(org.springframework.http.HttpMethod.valueOf("CHARGE")).uri("/a").retrieve()
                .toBodilessEntity());

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(e.retryable()).isFalse();
    }

    @Test
    void theJdksAllMethodRetryIsRefused() {
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "true");
        try {
            assertThatThrownBy(() -> new DefaultPlatformRestClientFactory(new RestClientProperties(true, null, null), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("enableAllMethodRetry");
        } finally {
            System.clearProperty("jdk.httpclient.enableAllMethodRetry");
        }
    }

    @Test
    void closingAStreamedBodyReturnsThePermit() throws Exception {
        server.enqueue(new MockResponse().setBody("stream"));
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, FAST, NO_BREAKER, new Bulkhead(true, null, 1)));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();
        var bulkhead = factory.bulkheads().bulkhead("rest-orders");

        try (java.io.InputStream body = client.get().uri("/a").retrieve().body(java.io.InputStream.class)) {
            assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isZero();
            assertThat(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("stream");
        }

        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void anEnormousRetryAfterFailsCleanly() {
        server.enqueue(status(429).addHeader("Retry-After", "9223372037"));
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, FAST, NO_BREAKER, new Bulkhead(true, null, 1)));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();

        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(e.status()).isEqualTo(429);
        assertThat(factory.bulkheads().bulkhead("rest-orders").getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void aRetryAfterBeyondTheDeadlineDoesNotWaitForAStalledBody() {
        server.enqueue(status(503).addHeader("Retry-After", "60").setBody("y".repeat(60)).throttleBody(1, 1, TimeUnit.SECONDS));
        RestClient client = client(new Defaults(null, null, Duration.ofSeconds(5), FAST, NO_BREAKER, null));

        long started = System.nanoTime();
        failure(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void bodiesThatFailCountAgainstTheBreaker() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("y".repeat(4096)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
            }
        });
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, new Retry(false, null, null, null, null, null),
                new CircuitBreaker(true, 50f, 4, 4, Duration.ofSeconds(30), 1), null));
        RestClient client = factory.builder("orders").baseUrl(server.url("/").toString()).build();

        for (int i = 0; i < 4; i++) {
            failure(() -> client.get().uri("/a").retrieve().body(String.class));
        }

        assertThat(factory.circuitBreakers().circuitBreaker("rest-orders").getState().name()).isEqualTo("OPEN");
    }

    @Test
    void aBodyThatOutlivesTheDeadlineIsReportedAsTheDeadline() {
        server.enqueue(new MockResponse().setBody("y".repeat(60)).throttleBody(10, 1, TimeUnit.SECONDS));
        RestClient client = client(new Defaults(null, Duration.ofSeconds(10), Duration.ofMillis(500), FAST, NO_BREAKER, null));

        RemoteCallException e = failure(() -> client.get().uri("/a").retrieve().body(String.class));

        assertThat(e.kind()).isEqualTo(Kind.DEADLINE);
        assertThat(e.problemStatus()).isEqualTo(504);
    }

    @Test
    void aBadRedirectTargetsQueryNeverReachesTheCause() {
        server.enqueue(status(307).addHeader("Location", "http://example.test/?q=PRIVATE%ZZ"));
        RestClient client = fastClient();

        Throwable e = catchThrowable(() -> client.get().uri("/a").retrieve().toBodilessEntity());

        assertThat(org.assertj.core.util.Throwables.getStackTrace(e)).doesNotContain("PRIVATE");
    }

    @Test
    void queriesAndUrlCredentialsStayOutOfObservations() throws Exception {
        server.enqueue(new MockResponse());
        var observed = new java.util.concurrent.atomic.AtomicReference<io.micrometer.observation.Observation.Context>();
        io.micrometer.observation.ObservationRegistry registry = io.micrometer.observation.ObservationRegistry.create();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(io.micrometer.observation.Observation.Context context) {
                observed.set(context);
            }
        });
        DefaultPlatformRestClientFactory factory = new DefaultPlatformRestClientFactory(new RestClientProperties(true, null, null), List.of(),
                () -> registry);
        factories.add(factory);
        String base = "http://user:pw@127.0.0.1:" + server.getPort();

        factory.builder("orders").baseUrl(base).build().get().uri("/lookup?token=SECRET").retrieve().toBodilessEntity();

        String tags = observed.get().getAllKeyValues().toString();
        assertThat(tags).doesNotContain("SECRET", "token=", "pw@").contains("/lookup");
    }

    @Test
    void aClosedFactoryRefusesNewClients() {
        DefaultPlatformRestClientFactory factory = factory(new Defaults(null, null, null, null, null, null));
        factory.builder("orders");

        factory.destroy();

        assertThatThrownBy(() -> factory.builder("other")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void anImpossibleTimeoutFailsAtBinding(String value) {
        Duration duration = Duration.parse(value.equals("0s") ? "PT0S" : "PT-1S");
        assertThatThrownBy(() -> new Defaults(duration, null, null, null, null, null)).hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new Defaults(null, duration, null, null, null, null)).hasMessageContaining("read-timeout");
        assertThatThrownBy(() -> new RestClientProperties.ClientOverride(duration, null, null, null, null, null))
                .hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new RestClientProperties.ClientOverride(null, duration, null, null, null, null))
                .hasMessageContaining("read-timeout");
    }

    @Test
    void aBlankClientNameIsRefused() {
        assertThatThrownBy(() -> factory(new Defaults(null, null, null, null, null, null)).builder(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
