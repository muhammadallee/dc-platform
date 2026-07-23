package ae.gov.dubaicustoms.platform.ratelimit.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.ratelimit.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a {@link RateLimitExceededException} thrown by a {@code @RateLimited} method to HTTP 429 with a
 * {@code Retry-After} header and an RFC-9457 {@link ProblemDetail} body. Registered only in a Spring
 * MVC application; the HTTP filter path writes its own 429 (it runs before MVC).
 *
 * @since 0.2.0
 */
@RestControllerAdvice
public class RateLimitExceptionAdvice {

    /**
     * @param exception the rate-limit rejection
     * @return a 429 response carrying the retry-after hint and a problem-detail body
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException exception) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        body.setTitle("Too Many Requests");
        long retrySeconds = Math.max(1, exception.retryAfter().toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retrySeconds))
                .body(body);
    }
}
