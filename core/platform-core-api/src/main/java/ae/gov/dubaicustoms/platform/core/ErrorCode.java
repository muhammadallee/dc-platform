package ae.gov.dubaicustoms.platform.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, machine-readable error identifier: UPPER_SNAKE, namespaced {@code DC-<CAP>-<NNNN>},
 * e.g. {@code DC-MSG-0001}.
 *
 * <p>Codes are contracts: they appear in problem responses, logs, and dashboards, and must never
 * be renamed or reused. Uniqueness across the platform is checked at build time (phase 4
 * registry test). Numbering convention: 0001&ndash;0399 business, 0400&ndash;0499 client,
 * 0500&ndash;0599 infrastructure.
 *
 * <pre>{@code
 * static final ErrorCode ORDER_NOT_FOUND = new ErrorCode("DC-ORDER-0404");
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe. The component is never {@code null}.
 *
 * @param value the code text; must match {@code ^DC-[A-Z]{2,8}-\d{4}$}
 * @since 0.1.0
 */
public record ErrorCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");

    /**
     * Validates the code format.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} does not match {@code ^DC-[A-Z]{2,8}-\d{4}$}
     */
    public ErrorCode {
        Objects.requireNonNull(value, "value must not be null");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "error code '" + value + "' must match DC-<CAP>-<NNNN> (regex ^DC-[A-Z]{2,8}-\\d{4}$), e.g. DC-MSG-0001");
        }
    }
}
