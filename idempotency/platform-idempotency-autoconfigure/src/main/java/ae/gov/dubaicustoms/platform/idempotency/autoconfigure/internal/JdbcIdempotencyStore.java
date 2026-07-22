package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Default {@link IdempotencyStore} over a single {@code platform_idempotency} table — the same
 * expiry-column pattern as the JDBC lock provider, minus the release. A key that has expired is
 * reclaimed in place; a live key rejects the duplicate.
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String RECLAIM_EXPIRED_SQL =
            "UPDATE platform_idempotency SET expires_at = ? WHERE idem_key = ? AND expires_at <= ?";
    private static final String INSERT_SQL =
            "INSERT INTO platform_idempotency (idem_key, expires_at) VALUES (?, ?)";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * @param jdbc the template addressing the datasource that holds {@code platform_idempotency}
     * @param clock the clock used to stamp and evaluate expiry; inject a fixed clock in tests
     */
    public JdbcIdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean putIfAbsent(String key, Duration ttl) {
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plus(ttl));

        // Reclaim an expired key in place — atomic under the row lock, so a fresh request proceeds.
        if (jdbc.update(RECLAIM_EXPIRED_SQL, expiresAt, key, nowTs) == 1) {
            return true;
        }
        // No expired/absent row: either the key is new (insert) or a live key already holds it.
        try {
            jdbc.update(INSERT_SQL, key, expiresAt);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
