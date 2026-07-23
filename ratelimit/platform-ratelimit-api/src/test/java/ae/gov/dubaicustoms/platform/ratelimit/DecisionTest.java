package ae.gov.dubaicustoms.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Guards the Decision value contract and its factory helpers. */
class DecisionTest {

    @Test
    void allowedHasNoWait() {
        Decision decision = Decision.allow();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    void deniedCarriesRetryAfter() {
        Decision decision = Decision.deny(Duration.ofSeconds(30));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsNullRetryAfter() {
        assertThatNullPointerException().isThrownBy(() -> new Decision(false, null));
    }
}
