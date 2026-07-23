package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import ae.gov.dubaicustoms.platform.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Behavior of the HTTP rate-limit filter: pass-through when allowed, 429 problem+json when denied. */
class RateLimitFilterTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void allowedRequestPassesThroughKeyedByIp() throws Exception {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(rateLimiter.tryAcquire(eq("http:10.0.0.1"), anyInt(), any())).thenReturn(Decision.allow());
        RateLimitFilter filter = new RateLimitFilter(
                rateLimiter, RateLimitMetrics.NOOP, false, 100, Duration.ofMinutes(1));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void deniedRequestWrites429WithRetryAfterAndProblemJson() throws Exception {
        StringWriter body = new StringWriter();
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        when(rateLimiter.tryAcquire(any(), anyInt(), any()))
                .thenReturn(Decision.deny(Duration.ofSeconds(5)));
        RateLimitFilter filter = new RateLimitFilter(
                rateLimiter, RateLimitMetrics.NOOP, false, 100, Duration.ofMinutes(1));

        filter.doFilter(request, response, chain);

        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "5");
        verify(response).setContentType("application/problem+json");
        verify(chain, never()).doFilter(any(), any());
        assertThat(body.toString()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    void keysByUserWhenConfigured() throws Exception {
        when(request.getRemoteUser()).thenReturn("alice");
        when(rateLimiter.tryAcquire(eq("http:alice"), anyInt(), any())).thenReturn(Decision.allow());
        RateLimitFilter filter = new RateLimitFilter(
                rateLimiter, RateLimitMetrics.NOOP, true, 100, Duration.ofMinutes(1));

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquire(eq("http:alice"), anyInt(), any());
        verify(chain).doFilter(request, response);
    }

    @Test
    void anonymousUserKeyWhenNoRemoteUser() throws Exception {
        when(request.getRemoteUser()).thenReturn(null);
        when(rateLimiter.tryAcquire(eq("http:anonymous"), anyInt(), any())).thenReturn(Decision.allow());
        RateLimitFilter filter = new RateLimitFilter(
                rateLimiter, RateLimitMetrics.NOOP, true, 100, Duration.ofMinutes(1));

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquire(eq("http:anonymous"), anyInt(), any());
    }
}
