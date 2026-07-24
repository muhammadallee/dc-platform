package ae.gov.dubaicustoms.platform.tck.locking;

import ae.gov.dubaicustoms.platform.locking.jdbc.JdbcLockProvider;
import ae.gov.dubaicustoms.platform.locking.spi.LockProvider;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Certifies the JDBC lock provider against {@link LockProviderTck} on an embedded H2 database,
 * docker-free. A {@link DriverManagerDataSource} (a real connection per call) is used rather than a
 * single-connection embedded datasource so the concurrency test exercises true row contention; the
 * unique in-memory URL with {@code DB_CLOSE_DELAY=-1} keeps the schema alive across connections.
 */
class JdbcLockProviderTckTest extends LockProviderTck {

    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() {
        String url = "jdbc:h2:mem:tck-lock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_lock (
                    lock_name  VARCHAR(255) NOT NULL,
                    token      VARCHAR(64)  NOT NULL,
                    expires_at TIMESTAMP    NOT NULL,
                    CONSTRAINT pk_platform_lock PRIMARY KEY (lock_name)
                )""");
    }

    @Override
    protected LockProvider provider() {
        return new JdbcLockProvider(jdbc, Clock.systemUTC());
    }

    @Override
    protected boolean enforcesExclusionUnderConcurrency() {
        // The JDBC provider acquires with two statements (reclaim-expired UPDATE, then INSERT) run on
        // separate connections. H2's in-memory engine does not reliably serialize that across many
        // simultaneous connections, so this docker-free run asserts only the safety property (>=1
        // winner, no error) plus the deterministic sequential-exclusion and fencing tests. Strict
        // one-winner exclusion is certified against a real database (PostgreSQL) under @Tag("docker").
        return false;
    }
}
