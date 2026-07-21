package ae.gov.dubaicustoms.platform.locking.jdbc.internal;

import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Releases a JDBC lock by deleting its row, fenced by the per-acquisition token so it never removes a
 * lock re-acquired by another holder after this one expired. Exists to keep {@link LockHandle#close()}
 * idempotent and non-throwing: a failed delete is logged, not propagated, because the lock will
 * auto-expire regardless.
 */
public final class JdbcLockHandle implements LockHandle {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcLockHandle.class);

    private static final String RELEASE_SQL =
            "DELETE FROM platform_lock WHERE lock_name = ? AND token = ?";

    private final JdbcTemplate jdbc;
    private final String name;
    private final String token;
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * @param jdbc the template to run the release against
     * @param name the held lock name
     * @param token the acquisition token that fences the release
     */
    public JdbcLockHandle(JdbcTemplate jdbc, String name, String token) {
        this.jdbc = jdbc;
        this.name = name;
        this.token = token;
    }

    @Override
    public void close() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            jdbc.update(RELEASE_SQL, name, token);
        } catch (DataAccessException e) {
            LOG.warn("Failed to release lock '{}'; it will auto-expire", name, e);
        }
    }
}
