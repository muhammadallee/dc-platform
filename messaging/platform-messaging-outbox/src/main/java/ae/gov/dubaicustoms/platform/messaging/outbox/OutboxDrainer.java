package ae.gov.dubaicustoms.platform.messaging.outbox;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.spi.EventTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Delivers outbox rows at least once through the {@link EventTransport}.
 *
 * <p>One transaction per batch, on the drainer's own bounded connections: claim due rows with
 * {@code FOR UPDATE SKIP LOCKED}, send each, record each, commit (D116). Row locks keep a concurrent instance off the
 * batch, and the database releases them when a pod dies, so no lease or recovery job exists; a kill can only repeat
 * the rows of the batch in flight. The batch limit is applied by fetching at most {@code batch-size} rows from the
 * cursor, because Oracle locks as it fetches: {@code ROWNUM} or {@code FETCH FIRST} would limit before skipping
 * locked rows, or are refused with {@code FOR UPDATE}.
 *
 * <p>A send failure ends the batch and pauses the drainer. Only a rejection by the sink counts toward parking
 * ({@link EventPublishException#rejected()}); an outage delays rows but never parks them, so recovery delivers
 * the whole backlog. Log lines name the exception class only: a transport message can carry hosts and credentials.
 *
 * <p>Thread-safe. Runs on one daemon thread.
 *
 * @since 1.0.0
 */
public final class OutboxDrainer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);

    // Stops before the transport and the pools close (bean destruction), after the web server's graceful phase.
    private static final int PHASE = DEFAULT_PHASE - 4096;

    private static final String CLAIM = """
            select event_id, event_type, event_version, destination, msg_key, payload, content_type, headers, attempts,
                rejections, principal
            from platform_outbox where status = 'PENDING' and next_attempt_at <= ?
            order by next_attempt_at for update skip locked
            """;
    private static final String MARK_SENT =
            "update platform_outbox set status = 'SENT', sent_at = ?, attempts = ?, last_error = null where event_id = ?";
    private static final String MARK_FAILED = "update platform_outbox set status = ?, attempts = ?, rejections = ?, "
            + "next_attempt_at = ?, last_error = ? where event_id = ?";
    // ROWNUM, not FETCH FIRST: a bounded DELETE on Oracle 19c.
    private static final String DELETE_SENT =
            "delete from platform_outbox where status = 'SENT' and sent_at < ? and rownum <= ?";
    private static final String COUNT_BY_STATUS = "select status, count(*) from platform_outbox group by status";
    private static final String OLDEST_PENDING = "select min(created_at) from platform_outbox where status = 'PENDING'";
    private static final String PROBE = "select event_id from platform_outbox where 1 = 0";

    private final DataSource dataSource;
    private final EventTransport transport;
    private final OutboxContext context;
    private final OutboxSettings settings;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter parkedCounter;

    private volatile boolean running;
    private volatile Thread thread;
    private Instant nextGauges = Instant.MIN;
    private Instant nextRetention = Instant.MIN;
    private Duration pause = Duration.ZERO;

    /**
     * Creates the drainer.
     *
     * @param dataSource the drainer's own bounded connections, never the application's pool; never {@code null}
     * @param transport sends the rows; never {@code null}
     * @param context restores the stored trace while sending; never {@code null}
     * @param settings the bounds; never {@code null}
     * @param clock the clock; never {@code null}
     * @param registry receives the metrics, or {@code null} for none
     */
    public OutboxDrainer(DataSource dataSource, EventTransport transport, OutboxContext context,
            OutboxSettings settings, Clock clock, MeterRegistry registry) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (registry != null) {
            Gauge.builder("platform.outbox.pending", pending, AtomicLong::get).register(registry);
            Gauge.builder("platform.outbox.parked", parked, AtomicLong::get).register(registry);
            Gauge.builder("platform.outbox.oldest.pending.age", oldestPendingSeconds, AtomicLong::get)
                    .baseUnit("seconds").register(registry);
        }
        this.sentCounter = registry != null ? registry.counter("platform.outbox.sent") : null;
        this.failedCounter = registry != null ? registry.counter("platform.outbox.failed") : null;
        this.parkedCounter = registry != null ? registry.counter("platform.outbox.parked.total") : null;
    }

    @Override
    public void start() {
        probeTable();
        running = true;
        Thread drainer = new Thread(this::run, "platform-outbox-drainer");
        drainer.setDaemon(true);
        thread = drainer;
        drainer.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread drainer = thread;
        if (drainer == null) {
            return;
        }
        // Not interrupted at first: the batch in flight finishes and commits, so its rows are not sent again.
        try {
            drainer.join(settings.stopTimeout().toMillis());
            if (drainer.isAlive()) {
                log.warn("The outbox batch in flight did not finish within {}; its rows may be sent again",
                        settings.stopTimeout());
                drainer.interrupt();
                drainer.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void probeTable() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(PROBE);
                ResultSet ignored = statement.executeQuery()) {
            log.debug("outbox table present");
        } catch (SQLException e) {
            throw new OutboxTableMissingException(e);
        }
    }

    private void run() {
        while (running) {
            boolean moreDue = false;
            try {
                moreDue = drainBatch() == settings.batchSize() && pause.isZero();
                housekeeping();
            } catch (SQLException | RuntimeException e) {
                // The database, not the sink: rows stay locked by nobody and are claimed again.
                log.warn("Outbox drain failed: {}", e.getClass().getName());
                increasePause();
            }
            if (!moreDue && running) {
                sleep(pause.isZero() ? settings.pollInterval() : pause);
            }
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void increasePause() {
        Duration doubled = pause.isZero() ? settings.initialBackoff() : pause.multipliedBy(2);
        pause = doubled.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : doubled;
    }

    /**
     * Claims and sends one batch, in one transaction.
     *
     * @return how many rows were claimed
     * @throws SQLException if the database failed; the transaction rolls back and the rows stay due
     */
    int drainBatch() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Row> rows = claim(connection);
                boolean failed = false;
                for (Row row : rows) {
                    if (!send(connection, row)) {
                        failed = true;
                        break;
                    }
                }
                connection.commit();
                if (failed) {
                    increasePause();
                } else if (!rows.isEmpty()) {
                    pause = Duration.ZERO;
                }
                return rows.size();
            } catch (SQLException | RuntimeException e) {
                rollback(connection);
                throw e;
            }
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("outbox rollback failed: {}", e.getClass().getName());
        }
    }

    // Stops reading at batch-size: Oracle locks rows as they are fetched, so the rest of the table stays unlocked.
    private List<Row> claim(Connection connection) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CLAIM)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setFetchSize(settings.batchSize());
            statement.setObject(1, OffsetDateTime.now(clock));
            try (ResultSet result = statement.executeQuery()) {
                while (rows.size() < settings.batchSize() && result.next()) {
                    rows.add(new Row(result.getString(1), result.getString(2), result.getInt(3), result.getString(4),
                            result.getString(5), result.getBytes(6), result.getString(7), result.getString(8),
                            result.getInt(9), result.getInt(10), result.getString(11)));
                }
            }
        }
        return rows;
    }

    private boolean send(Connection connection, Row row) throws SQLException {
        Map<String, String> stored = parseHeaders(row.headers());
        try (OutboxContext.Scope ignored = context.restore(stored)) {
            byte[] key = row.key() != null ? row.key().getBytes(StandardCharsets.UTF_8) : null;
            transport.send(row.destination(), key, row.payload(), messageHeaders(row, stored));
            markSent(connection, row);
            increment(sentCounter);
            return true;
        } catch (RuntimeException e) {
            markFailed(connection, row, e);
            return false;
        }
    }

    private static Map<String, String> messageHeaders(Row row, Map<String, String> stored) {
        Map<String, String> headers = new LinkedHashMap<>(stored);
        headers.put(EventEnvelope.EVENT_ID_HEADER, row.eventId());
        headers.put("eventType", row.eventType());
        headers.put("eventVersion", String.valueOf(row.eventVersion()));
        headers.put("contentType", row.contentType());
        if (row.principal() != null) {
            headers.put("principal", row.principal());
        }
        return headers;
    }

    private static Map<String, String> parseHeaders(String json) {
        try {
            return JdbcOutboxPublisher.JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored outbox row has unreadable headers", e);
        }
    }

    private void markSent(Connection connection, Row row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_SENT)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setObject(1, OffsetDateTime.now(clock));
            statement.setInt(2, row.attempts() + 1);
            statement.setString(3, row.eventId());
            statement.executeUpdate();
        }
    }

    private void markFailed(Connection connection, Row row, RuntimeException failure) throws SQLException {
        int attempts = row.attempts() + 1;
        boolean isRejection = failure instanceof EventPublishException publish && publish.rejected();
        int rejections = row.rejections() + (isRejection ? 1 : 0);
        boolean park = rejections >= settings.maxRejections();
        try (PreparedStatement statement = connection.prepareStatement(MARK_FAILED)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setString(1, park ? "PARKED" : "PENDING");
            statement.setInt(2, attempts);
            statement.setInt(3, rejections);
            statement.setObject(4, OffsetDateTime.now(clock).plus(settings.backoff(attempts)));
            statement.setString(5, failure.getClass().getName());
            statement.setString(6, row.eventId());
            statement.executeUpdate();
        }
        increment(failedCounter);
        if (park) {
            increment(parkedCounter);
            log.warn("Outbox event {} of type {} parked after {} rejections by {}", row.eventId(), row.eventType(),
                    rejections, row.destination());
        } else {
            log.warn("Outbox send to {} failed (attempt {}): {}", row.destination(), attempts,
                    failure.getClass().getName());
        }
    }

    private void housekeeping() throws SQLException {
        Instant now = clock.instant();
        if (!now.isBefore(nextGauges)) {
            refreshGauges();
            nextGauges = now.plus(settings.gaugeInterval());
        }
        if (!now.isBefore(nextRetention)) {
            deleteSent();
            nextRetention = now.plus(settings.retentionInterval());
        }
    }

    /**
     * Refreshes the backlog metrics. Runs here rather than on scrape, so a scrape never touches the database.
     *
     * @throws SQLException if the database failed
     */
    void refreshGauges() throws SQLException {
        long pendingRows = 0;
        long parkedRows = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement counts = connection.prepareStatement(COUNT_BY_STATUS);
                PreparedStatement oldest = connection.prepareStatement(OLDEST_PENDING)) {
            counts.setQueryTimeout(timeoutSeconds());
            oldest.setQueryTimeout(timeoutSeconds());
            try (ResultSet result = counts.executeQuery()) {
                while (result.next()) {
                    if ("PENDING".equals(result.getString(1))) {
                        pendingRows = result.getLong(2);
                    } else if ("PARKED".equals(result.getString(1))) {
                        parkedRows = result.getLong(2);
                    }
                }
            }
            try (ResultSet result = oldest.executeQuery()) {
                OffsetDateTime created = result.next() ? result.getObject(1, OffsetDateTime.class) : null;
                oldestPendingSeconds.set(created == null ? 0
                        : Math.max(0, Duration.between(created.toInstant(), clock.instant()).toSeconds()));
            }
        }
        pending.set(pendingRows);
        parked.set(parkedRows);
    }

    /**
     * Deletes sent rows past retention, in short bounded transactions. Never touches pending or parked rows.
     *
     * @return how many rows were deleted
     * @throws SQLException if the database failed
     */
    int deleteSent() throws SQLException {
        int total = 0;
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(settings.sentRetention());
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(DELETE_SENT)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setObject(1, cutoff);
            statement.setInt(2, settings.batchSize() * 20);
            int deleted;
            do {
                deleted = statement.executeUpdate();
                total += deleted;
            } while (deleted == settings.batchSize() * 20 && running);
        }
        return total;
    }

    private int timeoutSeconds() {
        return Math.toIntExact(settings.statementTimeout().toSeconds());
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /** One claimed row. */
    private record Row(String eventId, String eventType, int eventVersion, String destination, String key,
            byte[] payload, String contentType, String headers, int attempts, int rejections, String principal) {
    }
}
