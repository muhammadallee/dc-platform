package ae.gov.dubaicustoms.platform.locking.jdbc;

import ae.gov.dubaicustoms.platform.locking.jdbc.internal.JdbcLockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link LockProvider} backed by a single {@code platform_lock} table. A lock is a row keyed by name
 * carrying a per-acquisition token and an {@code expires_at} instant; expiry is data-driven (checked
 * on acquire) rather than timer-driven, so a crashed holder's lock is reclaimable without any
 * background sweeper.
 *
 * <p>Acquisition is two atomic statements: first take over an existing row only if it has expired
 * ({@code UPDATE … WHERE expires_at <= now}); if that touches no row, insert a fresh one, and treat a
 * primary-key collision as "already held". Both paths are race-safe under the table's row locking, so
 * two nodes never hold the same lock. Release is fenced by the token (see {@link JdbcLockHandle}).
 *
 * <p>Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class JdbcLockProvider implements LockProvider {

    private static final String TAKEOVER_EXPIRED_SQL =
            "UPDATE platform_lock SET token = ?, expires_at = ? WHERE lock_name = ? AND expires_at <= ?";
    private static final String INSERT_SQL =
            "INSERT INTO platform_lock (lock_name, token, expires_at) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * @param jdbc the template addressing the datasource that holds {@code platform_lock}
     * @param clock the clock used to stamp and evaluate expiry; inject a fixed clock in tests
     */
    public JdbcLockProvider(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String name, Duration atMost) {
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plus(atMost));
        String token = UUID.randomUUID().toString();

        // Reclaim an expired lock in place — atomic under the row lock, so only one node wins.
        if (jdbc.update(TAKEOVER_EXPIRED_SQL, token, expiresAt, name, nowTs) == 1) {
            return Optional.of(new JdbcLockHandle(jdbc, name, token));
        }
        // No expired/absent row was updated: either the name is free (insert) or a live lock holds it.
        try {
            jdbc.update(INSERT_SQL, name, token, expiresAt);
            return Optional.of(new JdbcLockHandle(jdbc, name, token));
        } catch (DuplicateKeyException alreadyHeld) {
            return Optional.empty();
        }
    }
}
