package ae.gov.dubaicustoms.platform.locking.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.locking.spi.LockHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Behavior tests for the JDBC lock provider against the shipped migration on H2 (no Docker). */
class JdbcLockProviderTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-21T10:00:00Z"));

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:db/migration-platform-locking/V1__create_platform_lock.sql")
                .build();
        jdbc = new JdbcTemplate(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void acquiresWhenFreeAndSkipsWhileHeld() {
        JdbcLockProvider provider = new JdbcLockProvider(jdbc, clock);

        Optional<LockHandle> first = provider.tryAcquire("job", Duration.ofMinutes(5));
        assertThat(first).isPresent();
        assertThat(provider.tryAcquire("job", Duration.ofMinutes(5))).isEmpty();

        first.orElseThrow().close();
        assertThat(provider.tryAcquire("job", Duration.ofMinutes(5))).isPresent();
    }

    @Test
    void reclaimsAnExpiredLock() {
        JdbcLockProvider provider = new JdbcLockProvider(jdbc, clock);
        provider.tryAcquire("job", Duration.ofSeconds(30)); // never released

        assertThat(provider.tryAcquire("job", Duration.ofSeconds(30))).isEmpty();
        clock.advance(Duration.ofSeconds(31));

        assertThat(provider.tryAcquire("job", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void releaseIsFencedByToken() {
        JdbcLockProvider provider = new JdbcLockProvider(jdbc, clock);
        LockHandle stale = provider.tryAcquire("job", Duration.ofSeconds(30)).orElseThrow();

        clock.advance(Duration.ofSeconds(31));
        LockHandle current = provider.tryAcquire("job", Duration.ofSeconds(30)).orElseThrow();

        stale.close(); // must NOT release the lock the new holder took over
        assertThat(provider.tryAcquire("job", Duration.ofSeconds(30))).isEmpty();

        current.close();
        assertThat(provider.tryAcquire("job", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void releaseIsIdempotent() {
        JdbcLockProvider provider = new JdbcLockProvider(jdbc, clock);
        LockHandle handle = provider.tryAcquire("job", Duration.ofMinutes(5)).orElseThrow();

        handle.close();
        handle.close();

        assertThat(provider.tryAcquire("job", Duration.ofMinutes(5))).isPresent();
    }

    /** A hand-advanced clock so expiry is exercised deterministically without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
