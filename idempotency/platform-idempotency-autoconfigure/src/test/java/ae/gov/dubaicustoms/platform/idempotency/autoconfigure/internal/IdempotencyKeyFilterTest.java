package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The Idempotency-Key HTTP filter: reject-duplicate-only, POST-only. */
class IdempotencyKeyFilterTest {

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyKeyFilter filter =
            new IdempotencyKeyFilter(store, "Idempotency-Key", Duration.ofHours(24));

    @Test
    void firstPostPassesThroughAndDuplicateGets409() throws Exception {
        when(store.putIfAbsent(eq("http:abc"), any(Duration.class))).thenReturn(true, false);

        MockHttpServletResponse first = doPost("abc");
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse duplicate = doPost("abc");
        assertThat(duplicate.getStatus()).isEqualTo(409);
    }

    @Test
    void requestsWithoutTheHeaderPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void nonPostRequestsPassThroughEvenWithTheHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("Idempotency-Key", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private MockHttpServletResponse doPost(String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.addHeader("Idempotency-Key", key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
