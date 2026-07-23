package ae.gov.dubaicustoms.platform.audit.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Behavior tests for the JDBC audit sink against the shipped migration on H2 (no Docker). */
class JdbcAuditSinkTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private JdbcAuditSink sink;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:db/migration-platform-audit/V1__create_platform_audit.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        sink = new JdbcAuditSink(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void appendsRowWithSerialisedDetails() {
        sink.write(new AuditEvent("order.create", "u1", "order:42", Outcome.SUCCESS,
                Instant.parse("2026-01-01T00:00:00Z"), "cid-1", Map.of("total", 199)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM platform_audit");
        assertThat(row.get("action")).isEqualTo("order.create");
        assertThat(row.get("actor")).isEqualTo("u1");
        assertThat(row.get("resource")).isEqualTo("order:42");
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("correlation_id")).isEqualTo("cid-1");
        assertThat(row.get("details")).asString().contains("\"total\":199");
    }

    @Test
    void allowsNullResourceAndCorrelationId() {
        sink.write(new AuditEvent("order.list", "system", null, Outcome.FAILURE,
                Instant.parse("2026-01-01T00:00:00Z"), null, Map.of()));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM platform_audit");
        assertThat(row.get("resource")).isNull();
        assertThat(row.get("correlation_id")).isNull();
        assertThat(row.get("outcome")).isEqualTo("FAILURE");
        assertThat(row.get("details")).asString().isEqualTo("{}");
    }

    @Test
    void isAppendOnlyAcrossMultipleEvents() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        sink.write(new AuditEvent("a", "u", null, Outcome.SUCCESS, at, null, Map.of()));
        sink.write(new AuditEvent("b", "u", null, Outcome.SUCCESS, at, null, Map.of()));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
