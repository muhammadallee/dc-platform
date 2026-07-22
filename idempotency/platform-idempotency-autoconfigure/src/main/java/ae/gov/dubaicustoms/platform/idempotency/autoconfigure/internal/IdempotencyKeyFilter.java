package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

/**
 * Rejects duplicate POSTs carrying a repeated {@code Idempotency-Key} header with HTTP 409, using the
 * {@link IdempotencyStore}. Off by default ({@code dc.platform.idempotency.http.enabled=false}).
 *
 * <p>Scope note: this is reject-duplicate only — the first response is NOT replayed for the duplicate
 * (response replay is out of scope for v1). Only POST is guarded; other methods pass through.
 */
public final class IdempotencyKeyFilter extends HttpFilter {

    private final transient IdempotencyStore store;
    private final String headerName;
    private final Duration ttl;

    /**
     * @param store the idempotency store to record HTTP keys in
     * @param headerName the request header carrying the idempotency key
     * @param ttl how long a seen HTTP key is remembered
     */
    public IdempotencyKeyFilter(IdempotencyStore store, String headerName, Duration ttl) {
        this.store = store;
        this.headerName = headerName;
        this.ttl = ttl;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String key = request.getHeader(headerName);
        if ("POST".equalsIgnoreCase(request.getMethod()) && key != null && !key.isBlank()) {
            if (!store.putIfAbsent("http:" + key, ttl)) {
                response.sendError(HttpServletResponse.SC_CONFLICT, "duplicate request");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
