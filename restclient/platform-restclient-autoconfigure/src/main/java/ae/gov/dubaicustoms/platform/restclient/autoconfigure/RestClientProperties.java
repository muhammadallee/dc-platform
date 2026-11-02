package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the REST client capability. Bound from {@code dc.platform.restclient.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformRestClientAutoConfiguration}
 * via {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param defaults timeouts and resilience applied when a client has no override
 * @param clients per-client overrides, keyed by the name passed to
 *        {@link ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory#builder(String)}
 * <p>{@code dc.platform.restclient.propagate-correlation} is gone: every client is instrumented with
 * the observation registry, so the W3C {@code traceparent} (the platform's only correlation id) is
 * always propagated and no custom header is sent. The key remains in the metadata as deprecated.
 *
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.restclient")
public record RestClientProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Timeouts and resilience applied when a client has no override. */
        Defaults defaults,
        /** Per-client overrides, keyed by client name. */
        Map<String, ClientOverride> clients) {

    /**
     * Normalizes nested defaults: Spring's constructor binding leaves unset object/map components
     * {@code null} rather than empty, so both are filled in here.
     */
    public RestClientProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null, null, null) : defaults;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /**
     * Resolves the effective settings for a named client: its own override, falling back setting by setting to
     * {@link #defaults()}.
     *
     * @param clientName the client name; never {@code null}
     * @return the effective settings, all present; never {@code null}
     */
    public Defaults timeoutsFor(String clientName) {
        ClientOverride override = clients.get(clientName);
        if (override == null) {
            return defaults;
        }
        return new Defaults(
                override.connectTimeout() != null ? override.connectTimeout() : defaults.connectTimeout(),
                override.readTimeout() != null ? override.readTimeout() : defaults.readTimeout(),
                override.deadline() != null ? override.deadline() : defaults.deadline(),
                defaults.retry().overriddenBy(override.retry()),
                defaults.circuitBreaker().overriddenBy(override.circuitBreaker()),
                defaults.bulkhead().overriddenBy(override.bulkhead()));
    }

    /**
     * Connect and read timeouts, deadline and resilience.
     *
     * @param connectTimeout connection timeout
     * @param readTimeout bound on one attempt's whole response, body included
     * @param deadline bound on one call, all attempts and waits included
     * @param retry retry of idempotent methods
     * @param circuitBreaker the per-client circuit breaker
     * @param bulkhead the per-client concurrency limit
     * @since 0.2.0
     */
    public record Defaults(
            /** Connection timeout. */
            @DefaultValue("2s") Duration connectTimeout,
            /** Bound on one attempt's whole response, body included. */
            @DefaultValue("10s") Duration readTimeout,
            /** Bound on one call, all attempts and waits included. */
            @DefaultValue("30s") Duration deadline,
            /** Retry of idempotent methods. */
            Retry retry,
            /** The per-client circuit breaker. */
            CircuitBreaker circuitBreaker,
            /** The per-client concurrency limit. */
            Bulkhead bulkhead) {

        /** Fills in defaults for any {@code null} component (used when constructed programmatically). */
        public Defaults {
            connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
            readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(10);
            deadline = deadline != null ? deadline : Duration.ofSeconds(30);
            requirePositive(connectTimeout, "connect-timeout");
            requirePositive(readTimeout, "read-timeout");
            requirePositive(deadline, "deadline");
            retry = Retry.DEFAULT.overriddenBy(retry);
            circuitBreaker = CircuitBreaker.DEFAULT.overriddenBy(circuitBreaker);
            bulkhead = Bulkhead.DEFAULT.overriddenBy(bulkhead);
        }
    }

    /**
     * Per-client override; any component may be {@code null} to fall back to {@link #defaults()}.
     *
     * @param connectTimeout connection timeout override
     * @param readTimeout read timeout override
     * @param deadline deadline override
     * @param retry retry overrides
     * @param circuitBreaker circuit breaker overrides
     * @param bulkhead bulkhead overrides
     * @since 0.2.0
     */
    public record ClientOverride(Duration connectTimeout, Duration readTimeout, Duration deadline, Retry retry,
            CircuitBreaker circuitBreaker, Bulkhead bulkhead) {

        /** Fails on a non-positive duration. */
        public ClientOverride {
            if (connectTimeout != null) {
                requirePositive(connectTimeout, "connect-timeout");
            }
            if (readTimeout != null) {
                requirePositive(readTimeout, "read-timeout");
            }
            if (deadline != null) {
                requirePositive(deadline, "deadline");
            }
        }
    }

    /**
     * Retry of idempotent methods. POST and PATCH are never retried, and configuring them fails startup.
     *
     * @param enabled whether to retry
     * @param maxAttempts total attempts, the first included
     * @param initialBackoff upper bound of the first wait; waits are jittered
     * @param multiplier growth of the bound between attempts
     * @param maxBackoff largest bound; a server's {@code Retry-After} is limited by the deadline instead
     * @param methods methods retried: a subset of GET, HEAD, OPTIONS, PUT, DELETE
     * @since 1.0.0
     */
    public record Retry(Boolean enabled, Integer maxAttempts, Duration initialBackoff, Double multiplier, Duration maxBackoff,
            Set<String> methods) {

        private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
        private static final Retry DEFAULT = new Retry(true, 3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(5), IDEMPOTENT);

        /** Fails on a method that is not idempotent, or an impossible value. */
        public Retry {
            if (methods != null) {
                Set<String> upper = new TreeSet<>();
                for (String method : methods) {
                    upper.add(method.toUpperCase(java.util.Locale.ROOT));
                }
                if (!IDEMPOTENT.containsAll(upper)) {
                    throw new IllegalArgumentException("retry.methods may only hold " + new TreeSet<>(IDEMPOTENT) + ", got " + upper
                            + ": a method that is not idempotent could be applied twice, so it is never retried");
                }
                methods = Set.copyOf(upper);
            }
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("retry.max-attempts must be at least 1, got " + maxAttempts);
            }
            if (multiplier != null && multiplier < 1) {
                throw new IllegalArgumentException("retry.multiplier must be at least 1, got " + multiplier);
            }
            if (initialBackoff != null) {
                requirePositive(initialBackoff, "retry.initial-backoff");
            }
            if (maxBackoff != null) {
                requirePositive(maxBackoff, "retry.max-backoff");
            }
        }

        Retry overriddenBy(Retry other) {
            if (other == null) {
                return this;
            }
            return new Retry(pick(other.enabled, enabled), pick(other.maxAttempts, maxAttempts),
                    pick(other.initialBackoff, initialBackoff), pick(other.multiplier, multiplier),
                    pick(other.maxBackoff, maxBackoff), pick(other.methods, methods));
        }
    }

    /**
     * The per-client circuit breaker.
     *
     * @param enabled whether to use it
     * @param failureRateThreshold failure percentage that opens it
     * @param slidingWindowSize calls measured
     * @param minimumCalls calls needed before it can open
     * @param openDuration how long it stays open
     * @param halfOpenCalls trial calls after that
     * @since 1.0.0
     */
    public record CircuitBreaker(Boolean enabled, Float failureRateThreshold, Integer slidingWindowSize, Integer minimumCalls,
            Duration openDuration, Integer halfOpenCalls) {

        private static final CircuitBreaker DEFAULT = new CircuitBreaker(true, 50f, 10, 10, Duration.ofSeconds(30), 3);

        /** Fails on an impossible value. */
        public CircuitBreaker {
            if (failureRateThreshold != null && (failureRateThreshold <= 0 || failureRateThreshold > 100)) {
                throw new IllegalArgumentException("circuit-breaker.failure-rate-threshold must be above 0 and at most 100, got "
                        + failureRateThreshold);
            }
            requireAtLeastOne(slidingWindowSize, "circuit-breaker.sliding-window-size");
            requireAtLeastOne(minimumCalls, "circuit-breaker.minimum-calls");
            requireAtLeastOne(halfOpenCalls, "circuit-breaker.half-open-calls");
            if (openDuration != null) {
                requirePositive(openDuration, "circuit-breaker.open-duration");
            }
        }

        CircuitBreaker overriddenBy(CircuitBreaker other) {
            if (other == null) {
                return this;
            }
            return new CircuitBreaker(pick(other.enabled, enabled), pick(other.failureRateThreshold, failureRateThreshold),
                    pick(other.slidingWindowSize, slidingWindowSize), pick(other.minimumCalls, minimumCalls),
                    pick(other.openDuration, openDuration), pick(other.halfOpenCalls, halfOpenCalls));
        }
    }

    /**
     * The per-client concurrency limit. Only a semaphore: a thread pool would defeat virtual threads. A full bulkhead refuses
     * at once, so waiting for room can never pass a deadline.
     *
     * @param enabled whether to use it
     * @param type {@link BulkheadType#SEMAPHORE}; {@link BulkheadType#THREAD_POOL} fails startup
     * @param maxConcurrent calls in flight at once
     * @since 1.0.0
     */
    public record Bulkhead(Boolean enabled, BulkheadType type, Integer maxConcurrent) {

        private static final Bulkhead DEFAULT = new Bulkhead(true, BulkheadType.SEMAPHORE, 25);

        /** Fails on a thread-pool type or an impossible value. */
        public Bulkhead {
            if (type == BulkheadType.THREAD_POOL) {
                throw new IllegalArgumentException("bulkhead.type THREAD_POOL is not supported: it would pin blocking calls to "
                        + "platform threads and defeat virtual threads. Use SEMAPHORE");
            }
            requireAtLeastOne(maxConcurrent, "bulkhead.max-concurrent");
        }

        Bulkhead overriddenBy(Bulkhead other) {
            if (other == null) {
                return this;
            }
            return new Bulkhead(pick(other.enabled, enabled), pick(other.type, type), pick(other.maxConcurrent, maxConcurrent));
        }
    }

    /**
     * Bulkhead implementations.
     *
     * @since 1.0.0
     */
    public enum BulkheadType {
        /** Limits calls with a semaphore, on the caller's thread. */
        SEMAPHORE,
        /** Named only so that asking for it fails with a reason. */
        THREAD_POOL
    }

    private static <T> T pick(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }

    private static void requireAtLeastOne(Integer value, String name) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, got " + value);
        }
    }
}
