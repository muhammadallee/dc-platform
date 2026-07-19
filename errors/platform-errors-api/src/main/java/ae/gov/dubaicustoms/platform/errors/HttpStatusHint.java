package ae.gov.dubaicustoms.platform.errors;

/**
 * HTTP status hint carried by {@link BusinessException}s so exception types stay free of
 * servlet-stack enums: a small, closed set wrapping the raw status int.
 *
 * <pre>{@code
 * ProblemDetail detail = ProblemDetail.forStatus(exception.statusHint().status());
 * }</pre>
 *
 * <p>Thread-safe (enum). Values never map outside the 4xx range: infrastructure failures are not
 * hinted — they are 500s by definition.
 *
 * @since 0.1.0
 */
public enum HttpStatusHint {

    /** 400 Bad Request — the request itself is malformed. */
    BAD_REQUEST(400),

    /** 404 Not Found — the addressed resource does not exist. */
    NOT_FOUND(404),

    /** 409 Conflict — the request clashes with current resource state. */
    CONFLICT(409),

    /** 422 Unprocessable Content — syntactically fine, semantically rejected (default hint). */
    UNPROCESSABLE(422);

    private final int status;

    HttpStatusHint(int status) {
        this.status = status;
    }

    /**
     * Returns the HTTP status code this hint maps to.
     *
     * @return the status code, always in the 4xx range
     */
    public int status() {
        return status;
    }
}
