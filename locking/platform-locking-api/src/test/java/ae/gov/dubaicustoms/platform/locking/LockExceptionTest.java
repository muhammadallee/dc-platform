package ae.gov.dubaicustoms.platform.locking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** Guards the lock error contract: a stable code and preserved cause. */
class LockExceptionTest {

    @Test
    void carriesTheStableInfraCodeAndCause() {
        RuntimeException cause = new RuntimeException("db down");

        LockException exception = new LockException("cannot reach lock table", cause);

        assertThat(exception.code().value()).isEqualTo("DC-LOCK-0500");
        assertThat(exception.getMessage()).isEqualTo("cannot reach lock table");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void rejectsNullMessage() {
        assertThatNullPointerException().isThrownBy(() -> new LockException(null, null));
    }
}
