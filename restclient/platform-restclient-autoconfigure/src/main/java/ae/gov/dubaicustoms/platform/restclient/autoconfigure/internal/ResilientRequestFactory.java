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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * <p>Thread-safe; each request belongs to one thread.
 */
final class ResilientRequestFactory implements ClientHttpRequestFactory {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    private final String client;
    private final HttpClient httpClient;
    private final RestClientProperties.Defaults settings;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final BiConsumer<String, String> attempts;

    /**
     * @param breaker the client's breaker, or {@code null} when disabled
     * @param bulkhead the client's bulkhead, or {@code null} when disabled
     */
    ResilientRequestFactory(String client, HttpClient httpClient, RestClientProperties.Defaults settings, CircuitBreaker breaker,
            Bulkhead bulkhead, BiConsumer<String, String> attempts) {
        this.client = client;
        this.httpClient = httpClient;
        this.settings = settings;
        this.breaker = breaker;
        this.bulkhead = bulkhead;
        this.attempts = attempts;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new ResilientRequest(uri, method);
    }

    private final class ResilientRequest extends AbstractClientHttpRequest {

        private final URI uri;
        private final HttpMethod method;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

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
            boolean idempotent = isIdempotent();
            int maxAttempts = Boolean.TRUE.equals(retry.enabled()) && retry.methods().contains(method.name()) ? retry.maxAttempts() : 1;
            long deadlineAt = System.nanoTime() + settings.deadline().toNanos();
            for (int attempt = 1;; attempt++) {
                Duration remaining = Duration.ofNanos(deadlineAt - System.nanoTime());
                if (remaining.isZero() || remaining.isNegative()) {
                    attempts.accept(client, "deadline");
                    throw new RemoteCallException(client, Kind.DEADLINE, 0, "", idempotent, null);
                }
                acquire();
                long started = System.nanoTime();
                Duration timeout = settings.readTimeout().compareTo(remaining) < 0 ? settings.readTimeout() : remaining;
                ClientHttpResponse response;
                try {
                    response = send(headers, timeout);
                } catch (IOException e) {
                    releaseBulkhead();
                    recordFailure(started, e);
                    RemoteCallException failure = classify(e, idempotent, timeout.equals(remaining));
                    attempts.accept(client, failure.kind().name().toLowerCase(java.util.Locale.ROOT));
                    Duration wait = attempt < maxAttempts && failure.kind() != Kind.DEADLINE ? backoff(attempt) : null;
                    if (wait == null || !fits(wait, deadlineAt) || !sleep(wait)) {
                        throw failure;
                    }
                    continue;
                } catch (RuntimeException | Error e) {
                    releaseBulkhead();
                    recordFailure(started, e);
                    throw e;
                }
                int status = status(response);
                recordResponse(started, status);
                attempts.accept(client, status >= 200 && status < 300 ? "success" : "status_" + status / 100 + "xx");
                if (attempt < maxAttempts && RETRYABLE_STATUSES.contains(status)) {
                    Duration wait = retryAfter(response.getHeaders()).orElse(backoff(attempt));
                    if (fits(wait, deadlineAt)) {
                        response.close();
                        releaseBulkhead();
                        if (sleep(wait)) {
                            continue;
                        }
                        throw new RemoteCallException(client, Kind.RESPONSE, status, "", idempotent, null);
                    }
                }
                return new GuardedResponse(response, idempotent);
            }
        }

        private ClientHttpResponse send(HttpHeaders headers, Duration timeout) throws IOException {
            JdkClientHttpRequestFactory delegate = new JdkClientHttpRequestFactory(httpClient);
            delegate.setReadTimeout(timeout);
            ClientHttpRequest request = delegate.createRequest(uri, method);
            request.getHeaders().putAll(headers);
            if (body.size() > 0) {
                body.writeTo(request.getBody());
            }
            return request.execute();
        }

        private boolean isIdempotent() {
            return !HttpMethod.POST.equals(method) && !HttpMethod.PATCH.equals(method);
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

    private void recordResponse(long started, int status) {
        if (breaker == null) {
            return;
        }
        long elapsed = System.nanoTime() - started;
        if (status >= 500) {
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, new IOException("status " + status));
        } else {
            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
        }
    }

    private RemoteCallException classify(IOException e, boolean idempotent, boolean timeoutWasTheDeadline) {
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException) {
            // Never reached the server, so a repeat is safe for any method.
            return new RemoteCallException(client, e instanceof ConnectException ? Kind.CONNECTION : Kind.TIMEOUT, 0, "", true, e);
        }
        if (e instanceof HttpTimeoutException) {
            return new RemoteCallException(client, timeoutWasTheDeadline ? Kind.DEADLINE : Kind.TIMEOUT, 0, "", idempotent, e);
        }
        return new RemoteCallException(client, Kind.CONNECTION, 0, "", idempotent, e);
    }

    private Duration backoff(int attempt) {
        RestClientProperties.Retry retry = settings.retry();
        double grown = retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), attempt - 1);
        double bound = Math.min(retry.maxBackoff().toMillis(), grown);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong((long) bound + 1));
    }

    private static boolean fits(Duration wait, long deadlineAt) {
        return wait.toNanos() < deadlineAt - System.nanoTime();
    }

    private static boolean sleep(Duration wait) {
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
    static java.util.Optional<Duration> retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = value.trim();
        try {
            return java.util.Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed))));
        } catch (NumberFormatException | ArithmeticException e) {
            try {
                Duration until = Duration.between(Instant.now(), ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
                return java.util.Optional.of(until.isNegative() ? Duration.ZERO : until);
            } catch (DateTimeParseException | ArithmeticException notADate) {
                return java.util.Optional.empty();
            }
        }
    }

    /** Holds the bulkhead permit until closed, and turns a failure reading the body into the one failure type. */
    private final class GuardedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final boolean idempotent;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GuardedResponse(ClientHttpResponse delegate, boolean idempotent) {
            this.delegate = delegate;
            this.idempotent = idempotent;
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
            };
        }

        private RemoteCallException readFailure(IOException e) {
            Kind kind = e instanceof HttpTimeoutException ? Kind.TIMEOUT : Kind.CONNECTION;
            return new RemoteCallException(client, kind, status(delegate), "", idempotent, e);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } finally {
                    releaseBulkhead();
                }
            }
        }
    }
}
