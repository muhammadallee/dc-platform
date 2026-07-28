package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.idempotency.IdempotencyStore;
import ae.gov.dubaicustoms.platform.tck.idempotency.IdempotencyStoreTck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Certifies the shipped JDBC idempotency store against {@link IdempotencyStoreTck} on H2, docker-free. */
class JdbcIdempotencyStoreTckTest extends IdempotencyStoreTck {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-22T10:00:00Z"));

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:db/migration-platform-idempotency/V1__create_platform_idempotency.sql")
                .build();
        jdbc = new JdbcTemplate(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Override
    protected IdempotencyStore store() {
        return new JdbcIdempotencyStore(jdbc, clock);
    }

    @Override
    protected void advanceTimePast(Duration ttl) {
        clock.advance(ttl.plusSeconds(1));
    }

    /** A hand-advanced clock so TTL expiry is exercised deterministically without sleeping. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

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
