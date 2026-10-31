package ae.gov.dubaicustoms.platform.messaging.outbox;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import ae.gov.dubaicustoms.platform.messaging.spi.EventSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the outbox row through the application's {@link DataSource}, inside the caller's transaction, so it
 * commits or rolls back with the business write. It refuses unless a transaction really owns a connection of that
 * {@code DataSource}: bound, and not auto-committing. A connection merely synchronized under another database's
 * transaction would auto-commit on its own and silently lose atomicity. (Spring exposes no way to verify ownership
 * further: a pool with {@code auto-commit=false}, a second transaction manager, and a read through this data source
 * inside that manager's transaction is not detected; use one transaction manager per outbox database.) It also
 * refuses anywhere under an {@code afterCommit} or {@code afterCompletion} callback: that thread still sees the
 * transaction's flags and connection, nothing commits again, and a {@code REQUIRED} method there joins the finished
 * transaction, so a frame cannot prove a new one. Serializes now: a later class change cannot alter a stored event.
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

    // The names the logging capability masks (D102): ending in one of these, ignoring case, '-' and '_'.
    private static final Set<String> CREDENTIAL_SUFFIXES =
            Set.of("password", "passwd", "pwd", "secret", "token", "apikey", "authorization", "cookie");
    // Only these leave the propagator: baggage or any other injected header must not reach a row.
    private static final Set<String> TRACE_HEADERS = Set.of("traceparent", "tracestate");

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
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        // A transaction manager binds the proxy's target, so that is the key to look the connection up by.
        this.dataSource = dataSource instanceof TransactionAwareDataSourceProxy proxy && proxy.getTargetDataSource() != null
                ? proxy.getTargetDataSource() : dataSource;
        this.jdbc = new JdbcTemplate(this.dataSource);
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void publish(String destination, EventEnvelope<?> event) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (!canPublish()) {
            throw new IllegalStateException("OutboxPublisher.publish needs an active, uncommitted transaction on the "
                    + "outbox's DataSource, or the event cannot commit with the business write: make the calling method "
                    + "@Transactional under a transaction manager for that DataSource");
        }
        Map<String, String> headers = new LinkedHashMap<>(event.headers());
        context.traceHeaders().forEach((name, value) -> {
            if (TRACE_HEADERS.contains(name)) {
                headers.put(name, value);
            }
        });
        headers.keySet().forEach(JdbcOutboxPublisher::refuseCredentialName);
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update(INSERT, event.eventId(), event.eventType(), event.eventVersion(), destination, event.key(),
                serializer.serialize(event.payload()), serializer.contentType(), toJson(headers), context.principal(),
                now, now);
    }

    @Override
    public boolean canPublish() {
        return TransactionSynchronizationManager.isActualTransactionActive() && ownsConnection()
                && !afterCommitPhase();
    }

    private boolean ownsConnection() {
        if (!(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)) {
            return false;
        }
        try {
            return !holder.getConnection().getAutoCommit();
        } catch (SQLException | IllegalStateException e) {
            // A closed connection, or a holder that no longer has one: nothing here can commit a row.
            return false;
        }
    }

    /**
     * Whether the current thread is anywhere under a transaction's after-commit or after-completion callback. Spring
     * offers no flag for this phase, so the stack decides, and it fails closed: a {@code REQUIRES_NEW} transaction opened
     * in such a callback is refused too, because a {@code REQUIRED} one looks the same and would lose the row.
     *
     * @return {@code true} if a row written now may never be committed
     */
    public static boolean afterCommitPhase() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                "org.springframework.transaction.support.TransactionSynchronizationUtils".equals(frame.getClassName())
                        && (frame.getMethodName().equals("invokeAfterCommit")
                                || frame.getMethodName().equals("invokeAfterCompletion"))));
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
