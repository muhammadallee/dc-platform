package ae.gov.dubaicustoms.platform.idempotency.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Behavior tests for the JDBC idempotency store against the shipped migration on H2 (no Docker). */
class JdbcIdempotencyStoreTest {

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

    @Test
    void firstKeyIsAcceptedAndDuplicateIsRejected() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(jdbc, clock);

        assertThat(store.putIfAbsent("order-1", Duration.ofHours(24))).isTrue();
        assertThat(store.putIfAbsent("order-1", Duration.ofHours(24))).isFalse();
        // A different key is independent.
        assertThat(store.putIfAbsent("order-2", Duration.ofHours(24))).isTrue();
    }

    @Test
    void keyIsAcceptedAgainAfterItsTtlExpires() {
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(jdbc, clock);
        assertThat(store.putIfAbsent("order-1", Duration.ofSeconds(30))).isTrue();
        assertThat(store.putIfAbsent("order-1", Duration.ofSeconds(30))).isFalse();

        clock.advance(Duration.ofSeconds(31));

        assertThat(store.putIfAbsent("order-1", Duration.ofSeconds(30))).isTrue();
    }

    /** A hand-advanced clock so TTL expiry is exercised deterministically without sleeping. */
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
