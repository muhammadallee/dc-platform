package ae.gov.dubaicustoms.platform.tck.audit;

import ae.gov.dubaicustoms.platform.audit.Outcome;
import ae.gov.dubaicustoms.platform.audit.jdbc.JdbcAuditSink;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Certifies the shipped JDBC audit sink against {@link AuditSinkTck} on H2, docker-free. */
class JdbcAuditSinkTckTest extends AuditSinkTck {

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

    @Override
    protected AuditSink sink() {
        return sink;
    }

    @Override
    protected long persistedCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    protected PersistedAudit lastPersisted() {
        return jdbc.queryForObject(
                "SELECT action, actor, resource, outcome, correlation_id FROM platform_audit ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new PersistedAudit(
                        rs.getString("action"),
                        rs.getString("actor"),
                        rs.getString("resource"),
                        Outcome.valueOf(rs.getString("outcome")),
                        rs.getString("correlation_id")));
    }
}
