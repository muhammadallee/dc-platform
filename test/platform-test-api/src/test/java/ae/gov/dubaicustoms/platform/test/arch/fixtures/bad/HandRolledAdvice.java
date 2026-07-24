package ae.gov.dubaicustoms.platform.test.arch.fixtures.bad;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Violates noHandRolledExceptionHandler: a user @RestControllerAdvice extending Spring's
// ResponseEntityExceptionHandler, competing with the platform's RFC 9457 error mapping.
@RestControllerAdvice
public class HandRolledAdvice extends ResponseEntityExceptionHandler {
}
