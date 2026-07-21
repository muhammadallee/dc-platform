package ae.gov.dubaicustoms.platform.data;

import ae.gov.dubaicustoms.platform.core.PlatformApi;

/**
 * The persistence conventions every platform entity should follow: physical naming, standard column
 * lengths, and the audit column names populated by Spring Data auditing.
 *
 * <p>These are shared constants, not enforcement — reference them from {@code @Column} declarations
 * and Flyway migrations so lengths and audit column names stay identical across services:
 *
 * <pre>{@code
 * @Column(name = "reference_code", length = PersistenceConventions.CODE_LENGTH)
 * private String referenceCode;
 * }</pre>
 *
 * <p>Utility holder; not instantiable. Thread-safe (constants only).
 *
 * @since 0.2.0
 */
@PlatformApi
public final class PersistenceConventions {

    /**
     * Physical naming: identifiers map to {@code snake_case} tables and columns. The JPA capability
     * installs the matching physical naming strategy; migrations must use the same convention.
     */
    public static final String PHYSICAL_NAMING = "snake_case";

    /** Length for short codes and enum-like tokens (for example a reference or status code). */
    public static final int CODE_LENGTH = 32;

    /** Length for names, titles, and other single-line human text. */
    public static final int NAME_LENGTH = 255;

    /** Length for a moderate free-text field that is not a full description. */
    public static final int SHORT_TEXT_LENGTH = 512;

    /** Length for a long free-text description. */
    public static final int DESCRIPTION_LENGTH = 2000;

    /** Length for a stored correlation id (matches the core capability's id width). */
    public static final int CORRELATION_ID_LENGTH = 64;

    /** Length for the {@link Money} storage column ({@code "<amount> <currencyCode>"}). */
    public static final int MONEY_LENGTH = 40;

    /** Audit column: creation timestamp, set once on insert. */
    public static final String CREATED_AT = "created_at";

    /** Audit column: identifier of the principal that created the row. */
    public static final String CREATED_BY = "created_by";

    /** Audit column: last-modification timestamp, updated on every write. */
    public static final String LAST_MODIFIED_AT = "last_modified_at";

    /** Audit column: identifier of the principal that last modified the row. */
    public static final String LAST_MODIFIED_BY = "last_modified_by";

    /** Optimistic-locking version column name. */
    public static final String VERSION = "version";

    private PersistenceConventions() {
    }
}
