package ae.gov.dubaicustoms.platform.ratelimit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.ratelimit.Decision;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Confirms RateLimiterProvider is a functional interface a provider can implement with a lambda. */
class RateLimiterProviderContractTest {

    @Test
    void isImplementableAsLambda() {
        RateLimiterProvider provider = (key, permits, window) ->
                permits > 0 ? Decision.allow() : Decision.deny(window);

        assertThat(provider.tryAcquire("k", 1, Duration.ofSeconds(1)).allowed()).isTrue();
        assertThat(provider.tryAcquire("k", 0, Duration.ofSeconds(1)).allowed()).isFalse();
    }
}
