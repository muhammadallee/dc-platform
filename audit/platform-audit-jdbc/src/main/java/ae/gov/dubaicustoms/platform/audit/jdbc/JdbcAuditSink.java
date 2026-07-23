package ae.gov.dubaicustoms.platform.audit.jdbc;

import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AuditSink} that appends each event as one row to the {@code platform_audit} table. The
 * {@code details} map is serialised to a JSON document so arbitrary structured context is retained
 * without a per-field schema. The table is append-only — audit records are never updated or deleted
 * by the platform.
 *
 * <p>Runs on the audit background worker, so the insert may block on the datasource without affecting
 * request latency. Thread-safe; a single instance is shared platform-wide.
 *
 * @since 0.2.0
 */
public final class JdbcAuditSink implements AuditSink {

    private static final String INSERT_SQL =
            "INSERT INTO platform_audit "
                    + "(action, actor, resource, outcome, occurred_at, correlation_id, details) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc the template addressing the datasource that holds {@code platform_audit}
     * @param objectMapper serialises the event details map to JSON
     */
    public JdbcAuditSink(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(AuditEvent event) {
        jdbc.update(INSERT_SQL,
                event.action(),
                event.actor(),
                event.resource(),
                event.outcome().name(),
                Timestamp.from(event.at()),
                event.correlationId(),
                serialize(event));
    }

    private String serialize(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.details());
        } catch (JsonProcessingException e) {
            // A non-serialisable details map is a programming error at the call site, not a runtime
            // condition to swallow; surface it so the audit worker logs and drops this one event.
            throw new IllegalArgumentException(
                    "audit details for action '" + event.action() + "' are not JSON-serialisable", e);
        }
    }
}
