package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import ae.gov.dubaicustoms.platform.restclient.RemoteCallException.Kind;
import ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Sends a call under its deadline, retry, circuit breaker and bulkhead, in that order from the outside (D126). It sits
 * below every interceptor, so each attempt carries what they added, and {@code RestClient}'s one observation covers the
 * whole call. Attempts are reported to the listener as {@code (client, outcome)}.
 *
 * <p>The request body is held in memory, since an attempt may need it again. A method not known to be idempotent uses a
 * client that does not follow redirects, because a 307 or 308 would send it again. A call's bulkhead permit, and the
 * breaker's verdict on a non-failing status, wait for the response to close: until then its body may still fail.
 *
 * <p>Thread-safe; each request belongs to one thread.
 */
final class ResilientRequestFactory implements ClientHttpRequestFactory {

    private static final long TIMER_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
    private static final Set<HttpMethod> IDEMPOTENT = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT,
            HttpMethod.DELETE);

    private final String client;
    private final HttpClient following;
    private final HttpClient direct;
    private final RestClientProperties.Defaults settings;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final BiConsumer<String, String> attempts;

    /**
     * @param following the transport for idempotent methods, following redirects
     * @param direct the transport for any other method, not following them
     * @param breaker the client's breaker, or {@code null} when disabled
     * @param bulkhead the client's bulkhead, or {@code null} when disabled
     */
    ResilientRequestFactory(String client, HttpClient following, HttpClient direct, RestClientProperties.Defaults settings,
            CircuitBreaker breaker, Bulkhead bulkhead, BiConsumer<String, String> attempts) {
        this.client = client;
        this.following = following;
        this.direct = direct;
        this.settings = settings;
        this.breaker = breaker;
        this.bulkhead = bulkhead;
        this.attempts = attempts;
    }

    /** An allowlist: a method this code has never heard of could do anything, and {@link HttpMethod} is case-sensitive. */
    static boolean isIdempotent(HttpMethod method) {
        return IDEMPOTENT.contains(method);
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new ResilientRequest(uri, method);
    }

    private final class ResilientRequest extends AbstractClientHttpRequest {

        private final URI uri;
        private final HttpMethod method;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        // From here, so a body written slowly by the caller's callback counts too.
        private final long deadlineAt = System.nanoTime() + settings.deadline().toNanos();

        private ResilientRequest(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return body;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) {
            RestClientProperties.Retry retry = settings.retry();
            boolean idempotent = isIdempotent(method);
            boolean retries = idempotent && Boolean.TRUE.equals(retry.enabled()) && retry.methods().contains(method.name());
            int maxAttempts = retries ? retry.maxAttempts() : 1;
            for (int attempt = 1;; attempt++) {
                Duration remaining = remaining();
                if (remaining.isZero() || remaining.isNegative()) {
                    attempts.accept(client, "deadline");
                    throw new RemoteCallException(client, Kind.DEADLINE, 0, "", idempotent, null);
                }
                acquire();
                long started = System.nanoTime();
                boolean cutByDeadline = settings.readTimeout().compareTo(remaining) >= 0;
                Duration timeout = cutByDeadline ? remaining : settings.readTimeout();
                ClientHttpResponse response;
                try {
                    response = send(headers, timeout, idempotent);
                } catch (IOException e) {
                    releaseBulkhead();
                    recordFailure(started, e);
                    RemoteCallException failure = classify(e, idempotent, cutByDeadline);
                    attempts.accept(client, failure.kind().name().toLowerCase(Locale.ROOT));
                    if (attempt < maxAttempts && failure.kind() != Kind.DEADLINE && waited(backoff(attempt))) {
                        continue;
                    }
                    throw failure;
                } catch (RuntimeException | Error e) {
                    releaseBulkhead();
                    recordFailure(started, e);
                    throw e;
                }
                int status = status(response);
                attempts.accept(client, status >= 200 && status < 300 ? "success" : "status_" + status / 100 + "xx");
                if (attempt < maxAttempts && RETRYABLE_STATUSES.contains(status)) {
                    int thisAttempt = attempt;
                    Duration wait = retryAfter(response.getHeaders()).orElseGet(() -> backoff(thisAttempt));
                    recordStatus(started, status);
                    closeWithoutDraining(response);
                    releaseBulkhead();
                    if (waited(wait)) {
                        continue;
                    }
                    // At once: reading this body could take as long as the attempt has left.
                    throw new RemoteCallException(client, Kind.RESPONSE, status, "", idempotent, null);
                }
                if (status >= 500) {
                    recordStatus(started, status);
                }
                return new GuardedResponse(response, idempotent, started, status < 500, started + timeout.toNanos(), cutByDeadline);
            }
        }

        private Duration remaining() {
            return Duration.ofNanos(deadlineAt - System.nanoTime());
        }

        /** False if the wait would pass the deadline, or was interrupted. */
        private boolean waited(Duration wait) {
            if (wait.compareTo(remaining()) >= 0) {
                return false;
            }
            try {
                Thread.sleep(wait);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private ClientHttpResponse send(HttpHeaders headers, Duration timeout, boolean idempotent) throws IOException {
            JdkClientHttpRequestFactory delegate = new JdkClientHttpRequestFactory(idempotent ? following : direct);
            delegate.setReadTimeout(timeout);
            ClientHttpRequest request = delegate.createRequest(uri, method);
            request.getHeaders().putAll(headers);
            if (body.size() > 0) {
                body.writeTo(request.getBody());
            }
            return request.execute();
        }
    }

    private void acquire() {
        if (breaker != null && !breaker.tryAcquirePermission()) {
            attempts.accept(client, "circuit_open");
            throw new RemoteCallException(client, Kind.CIRCUIT_OPEN, 0, "", true, null);
        }
        if (bulkhead != null && !bulkhead.tryAcquirePermission()) {
            if (breaker != null) {
                breaker.releasePermission();
            }
            attempts.accept(client, "bulkhead_full");
            throw new RemoteCallException(client, Kind.BULKHEAD_FULL, 0, "", true, null);
        }
    }

    private void releaseBulkhead() {
        if (bulkhead != null) {
            bulkhead.onComplete();
        }
    }

    private void recordFailure(long started, Throwable failure) {
        if (breaker != null) {
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, failure);
        }
    }

    private void recordStatus(long started, int status) {
        if (status >= 500) {
            recordFailure(started, new IOException("status " + status));
        } else if (breaker != null) {
            breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private RemoteCallException classify(IOException e, boolean idempotent, boolean cutByDeadline) {
        IOException cause = sanitized(e);
        // A connection never made: nothing arrived, so a repeat is safe for any method. A method that might have sent
        // something first never follows a redirect here, so this is about the only request it made.
        if (e instanceof HttpConnectTimeoutException) {
            return new RemoteCallException(client, Kind.TIMEOUT, 0, "", true, cause);
        }
        if (e instanceof ConnectException) {
            return new RemoteCallException(client, Kind.CONNECTION, 0, "", true, cause);
        }
        if (e instanceof HttpTimeoutException) {
            return new RemoteCallException(client, cutByDeadline ? Kind.DEADLINE : Kind.TIMEOUT, 0, "", idempotent, cause);
        }
        return new RemoteCallException(client, Kind.CONNECTION, 0, "", idempotent, cause);
    }

    /** A transport message can quote a URL, as with a bad redirect target, so its query and credentials go, and so do deeper causes. */
    private static IOException sanitized(IOException e) {
        String message = e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\?\\S*", "?…").replaceAll("//[^/\\s@]*@", "//");
        IOException clean = new IOException(e.getClass().getName() + message);
        clean.setStackTrace(e.getStackTrace());
        return clean;
    }

    private Duration backoff(int attempt) {
        RestClientProperties.Retry retry = settings.retry();
        double grown = retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), attempt - 1);
        double bound = Math.min(retry.maxBackoff().toMillis(), grown);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong((long) bound + 1));
    }

    /**
     * Spring's close reads what is left of the body first, so a huge or stalled one would hold the caller until its timeout.
     * Closing the stream first cancels it instead. A body already read to its end keeps its connection.
     */
    private static void closeWithoutDraining(ClientHttpResponse response) {
        try {
            response.getBody().close();
        } catch (IOException | RuntimeException e) {
            // Nothing to do: the response is being abandoned.
        }
        response.close();
    }

    private static int status(ClientHttpResponse response) {
        try {
            HttpStatusCode code = response.getStatusCode();
            return code.value();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Seconds or an HTTP date; anything else is ignored. */
    static Optional<Duration> retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed))));
        } catch (NumberFormatException e) {
            try {
                Duration until = Duration.between(Instant.now(), ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
                return Optional.of(until.isNegative() ? Duration.ZERO : until);
            } catch (DateTimeException | ArithmeticException notADate) {
                return Optional.empty();
            }
        }
    }

    /**
     * Holds the permit until closed, gives the breaker its verdict on the body, and turns a failure reading the body into
     * the one failure type. Closing its stream closes it: Spring leaves a streamed response for the caller to close.
     */
    private final class GuardedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final boolean idempotent;
        private final long started;
        private final long expiresAt;
        private final boolean cutByDeadline;
        private final AtomicBoolean verdictPending;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GuardedResponse(ClientHttpResponse delegate, boolean idempotent, long started, boolean verdictPending, long expiresAt,
                boolean cutByDeadline) {
            this.delegate = delegate;
            this.idempotent = idempotent;
            this.started = started;
            this.verdictPending = new AtomicBoolean(verdictPending);
            this.expiresAt = expiresAt;
            this.cutByDeadline = cutByDeadline;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return new FilterInputStream(delegate.getBody()) {
                @Override
                public int read() {
                    try {
                        return super.read();
                    } catch (IOException e) {
                        throw readFailure(e);
                    }
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    try {
                        return super.read(target, offset, length);
                    } catch (IOException e) {
                        throw readFailure(e);
                    }
                }

                @Override
                public void close() {
                    GuardedResponse.this.close();
                }
            };
        }

        /**
         * Spring's timer closes the stream when an attempt's time is up, so the clock is what tells a timeout from a loss. The
         * timer truncates to milliseconds and can fire just before the exact expiry, hence the margin.
         */
        private RemoteCallException readFailure(IOException e) {
            if (verdictPending.compareAndSet(true, false)) {
                recordFailure(started, e);
            }
            boolean timedOut = e instanceof HttpTimeoutException || System.nanoTime() - expiresAt >= -TIMER_MARGIN_NANOS;
            Kind kind = !timedOut ? Kind.CONNECTION : cutByDeadline ? Kind.DEADLINE : Kind.TIMEOUT;
            return new RemoteCallException(client, kind, status(delegate), "", idempotent, sanitized(e));
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (verdictPending.compareAndSet(true, false)) {
                    recordStatus(started, 200);
                }
                try {
                    closeWithoutDraining(delegate);
                } finally {
                    releaseBulkhead();
                }
            }
        }
    }
}
