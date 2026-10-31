package ae.gov.dubaicustoms.platform.messaging.outbox;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the outbox row through the application's {@link DataSource}, inside the caller's transaction, so it
 * commits or rolls back with the business write. It refuses to write unless a transaction holds a connection of
 * that {@code DataSource} (a publish under another transaction manager would auto-commit on its own connection and
 * silently lose atomicity). Serializes now: a later class change cannot alter a stored event.
 *
 * <p>Thread-safe.
 *
 * @since 1.0.0
 */
public final class JdbcOutboxPublisher implements OutboxPublisher {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT = """
            insert into platform_outbox (event_id, event_type, event_version, destination, msg_key, payload, content_type,
                headers, principal, created_at, next_attempt_at, attempts, rejections, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 'PENDING')
            """;

    // The same names the logging capability masks (D102): a name ending in one of these, ignoring case, '-' and '_'.
    private static final Set<String> CREDENTIAL_SUFFIXES =
            Set.of("password", "passwd", "secret", "token", "apikey", "authorization", "cookie");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final EventSerializer serializer;
    private final OutboxContext context;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param dataSource the application's data source, the one the business write uses; never {@code null}
     * @param serializer serializes the payload; never {@code null}
     * @param context supplies the trace and principal to store; never {@code null}
     * @param clock supplies the created time; never {@code null}
     */
    public JdbcOutboxPublisher(DataSource dataSource, EventSerializer serializer, OutboxContext context, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void publish(String destination, EventEnvelope<?> event) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("OutboxPublisher.publish needs an active transaction on the outbox's "
                    + "DataSource, or the event cannot commit with the business write: make the calling method "
                    + "@Transactional under a transaction manager for that DataSource");
        }
        event.headers().keySet().forEach(JdbcOutboxPublisher::refuseCredentialName);
        Map<String, String> headers = new LinkedHashMap<>(event.headers());
        headers.putAll(context.traceHeaders());
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update(INSERT, event.eventId(), event.eventType(), event.eventVersion(), destination, event.key(),
                serializer.serialize(event.payload()), serializer.contentType(), toJson(headers), context.principal(),
                now, now);
    }

    private static void refuseCredentialName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (CREDENTIAL_SUFFIXES.stream().anyMatch(normalized::endsWith)) {
            throw new IllegalArgumentException("Outbox rows must not hold credentials; refused header: " + name);
        }
    }

    private static String toJson(Map<String, String> headers) {
        try {
            return JSON.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox headers could not be stored", e);
        }
    }
}
