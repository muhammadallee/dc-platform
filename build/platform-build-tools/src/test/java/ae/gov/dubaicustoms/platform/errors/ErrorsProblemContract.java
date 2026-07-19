package ae.gov.dubaicustoms.platform.errors;

import org.springframework.http.ProblemDetail;

// Fixture: the errors api root package MAY use org.springframework.http (RFC-9457 standard model).
public interface ErrorsProblemContract {
    void customize(ProblemDetail detail, Throwable source);
}
