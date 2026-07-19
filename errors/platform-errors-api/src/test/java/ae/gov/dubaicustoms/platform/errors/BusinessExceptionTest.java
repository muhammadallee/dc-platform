package ae.gov.dubaicustoms.platform.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import ae.gov.dubaicustoms.platform.core.PlatformException;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    private static final ErrorCode CODE = new ErrorCode("DC-TEST-0001");

    @Test
    void defaultsToUnprocessableHint() {
        BusinessException exception = new BusinessException(CODE, "rule violated");

        assertThat(exception.statusHint()).isEqualTo(HttpStatusHint.UNPROCESSABLE);
        assertThat(exception.statusHint().status()).isEqualTo(422);
    }

    @Test
    void carriesCodeAndMessageAsPlatformException() {
        BusinessException exception = new BusinessException(CODE, "rule violated");

        assertThat(exception).isInstanceOf(PlatformException.class);
        assertThat(exception.code()).isEqualTo(CODE);
        assertThat(exception.getMessage()).isEqualTo("rule violated");
    }

    @Test
    void notFoundHintsAt404() {
        NotFoundException exception = new NotFoundException(new ErrorCode("DC-TEST-0404"), "gone");

        assertThat(exception.statusHint()).isEqualTo(HttpStatusHint.NOT_FOUND);
        assertThat(exception.statusHint().status()).isEqualTo(404);
        assertThat(exception).isInstanceOf(BusinessException.class);
    }

    @Test
    void conflictHintsAt409() {
        ConflictException exception = new ConflictException(new ErrorCode("DC-TEST-0409"), "taken");

        assertThat(exception.statusHint()).isEqualTo(HttpStatusHint.CONFLICT);
        assertThat(exception.statusHint().status()).isEqualTo(409);
    }

    @Test
    void everyHintIsClientError() {
        for (HttpStatusHint hint : HttpStatusHint.values()) {
            assertThat(hint.status()).isBetween(400, 499);
        }
    }

    @Test
    void rejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new BusinessException(null, "message"));
        assertThatNullPointerException().isThrownBy(() -> new BusinessException(CODE, null));
        assertThatNullPointerException().isThrownBy(() -> new BusinessException(CODE, "message", null));
    }
}
