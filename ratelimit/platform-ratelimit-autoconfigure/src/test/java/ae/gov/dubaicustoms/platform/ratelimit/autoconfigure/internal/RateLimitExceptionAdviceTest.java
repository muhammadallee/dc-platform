package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.ratelimit.RateLimitExceededException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** Verifies the MVC advice maps a rate-limit rejection to 429 with Retry-After and a ProblemDetail. */
class RateLimitExceptionAdviceTest {

    private final RateLimitExceptionAdvice advice = new RateLimitExceptionAdvice();

    @Test
    void mapsToTooManyRequestsWithRetryAfterAndProblemDetail() {
        ResponseEntity<ProblemDetail> response = advice.handleRateLimitExceeded(
                new RateLimitExceededException("search", Duration.ofSeconds(30)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(429);
        assertThat(response.getBody().getTitle()).isEqualTo("Too Many Requests");
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        ResponseEntity<ProblemDetail> response = advice.handleRateLimitExceeded(
                new RateLimitExceededException("search", Duration.ofMillis(200)));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }
}
