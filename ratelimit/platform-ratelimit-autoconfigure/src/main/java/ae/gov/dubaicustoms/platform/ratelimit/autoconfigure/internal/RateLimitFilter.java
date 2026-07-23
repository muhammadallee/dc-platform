package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Applies a single rate limit to every inbound HTTP request, keyed by caller identity or client IP.
 * Off by default ({@code dc.platform.ratelimit.http.enabled=false}); when on, an over-limit request is
 * answered with HTTP 429, a {@code Retry-After} header, and an RFC-9457 {@code application/problem+json}
 * body — written directly, since a servlet filter runs before Spring MVC's exception handling.
 *
 * @since 0.2.0
 */
public final class RateLimitFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;

    /** Bucket prefix so HTTP-filter keys never collide with {@code @RateLimited} method buckets. */
    static final String BUCKET = "http";

    private final transient RateLimiter rateLimiter;
    private final transient RateLimitMetrics metrics;
    private final boolean keyByUser;
    private final int permits;
    private final transient Duration window;

    /**
     * @param rateLimiter the limiter permits are consumed from
     * @param metrics the decision metrics sink
     * @param keyByUser bucket by servlet remote user when {@code true}, otherwise by client IP
     * @param permits permits allowed per window per key
     * @param window the window length
     */
    public RateLimitFilter(RateLimiter rateLimiter, RateLimitMetrics metrics,
            boolean keyByUser, int permits, Duration window) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.keyByUser = keyByUser;
        this.permits = permits;
        this.window = window;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String caller = keyByUser
                ? Optional.ofNullable(request.getRemoteUser()).orElse("anonymous")
                : request.getRemoteAddr();
        Decision decision = rateLimiter.tryAcquire(BUCKET + ":" + caller, permits, window);
        metrics.record(BUCKET, decision.allowed());
        if (!decision.allowed()) {
            writeTooManyRequests(response, decision.retryAfter());
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, Duration retryAfter) throws IOException {
        long retrySeconds = Math.max(1, retryAfter.toSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retrySeconds));
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                        + "\"detail\":\"Rate limit exceeded\"}");
    }
}
