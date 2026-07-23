package ae.gov.dubaicustoms.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Guards the rate-limit exception contract: it carries the retry-after hint and names the bucket. */
class RateLimitExceededExceptionTest {

    @Test
    void carriesRetryAfterAndNamesBucket() {
        RateLimitExceededException exception =
                new RateLimitExceededException("search", Duration.ofSeconds(5));

        assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(exception).hasMessageContaining("search");
    }

    @Test
    void rejectsNullRetryAfter() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RateLimitExceededException("search", null));
    }
}
