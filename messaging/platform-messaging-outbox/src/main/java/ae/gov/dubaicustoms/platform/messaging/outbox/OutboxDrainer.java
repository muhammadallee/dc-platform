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
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
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
 * the whole backlog. A row whose stored headers cannot be read is parked at once and does not stop the batch. A stop
 * ends the batch after the send in flight, and commits what was sent. Parking metrics and WARNs are published after
 * the commit, so they never describe a state that rolled back. Log lines name event ids and exception classes only:
 * a transport message or a destination can carry hosts and credentials.
 *
 * <p>Backlog metrics and retention run on a second thread, so a blocked send or a pause delays neither, and a failure
 * of one does not stop the other. {@code platform.outbox.gauges.age} says how old the backlog figures are.
 *
 * <p>Thread-safe.
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
    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter parkedCounter;
    private final List<Gauge> gauges = new ArrayList<>();

    private volatile Thread worker;
    private volatile Thread maintenance;
    private Thread lastWorker;
    private volatile boolean stopping;
    private volatile boolean inBatch;
    private volatile Instant lastGaugeRefresh;
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
        this.registry = registry;
        this.sentCounter = registry != null ? registry.counter("platform.outbox.sent") : null;
        this.failedCounter = registry != null ? registry.counter("platform.outbox.failed") : null;
        this.parkedCounter = registry != null ? registry.counter("platform.outbox.parked.total") : null;
        registerGauges();
    }

    // A second context on the same registry must not read this drainer's state: stop() removes them again.
    private void registerGauges() {
        if (registry == null || !gauges.isEmpty()) {
            return;
        }
        gauges.add(Gauge.builder("platform.outbox.pending", pending, AtomicLong::get).register(registry));
        gauges.add(Gauge.builder("platform.outbox.parked", parked, AtomicLong::get).register(registry));
        gauges.add(Gauge.builder("platform.outbox.oldest.pending.age", oldestPendingSeconds, AtomicLong::get)
                .baseUnit("seconds").register(registry));
        gauges.add(Gauge.builder("platform.outbox.gauges.age", this, OutboxDrainer::gaugesAgeSeconds)
                .baseUnit("seconds").register(registry));
        gauges.add(Gauge.builder("platform.outbox.drainer.running", this, OutboxDrainer::workerAlive).register(registry));
    }

    // Infinite until the first refresh: "never measured" must not read as "no backlog".
    private double gaugesAgeSeconds() {
        Instant last = lastGaugeRefresh;
        return last == null ? Double.POSITIVE_INFINITY : Duration.between(last, clock.instant()).toSeconds();
    }

    private double workerAlive() {
        Thread current = worker;
        return current != null && current.isAlive() ? 1 : 0;
    }

    @Override
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        // A send that ignored the stop may still own its batch; a new worker must not run beside it.
        if (lastWorker != null && lastWorker.isAlive()) {
            throw new IllegalStateException("The previous outbox batch has not finished; try again when it has");
        }
        probeTable();
        registerGauges();
        stopping = false;
        worker = daemon(this::runWorker, "platform-outbox-drainer");
        lastWorker = worker;
        maintenance = daemon(this::runMaintenance, "platform-outbox-maintenance");
        worker.start();
        maintenance.start();
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public synchronized void stop() {
        stopping = true;
        Thread drainer = worker;
        Thread housekeeper = maintenance;
        worker = null;
        maintenance = null;
        if (housekeeper != null) {
            housekeeper.interrupt();
        }
        if (drainer != null) {
            await(drainer);
        }
        joinBriefly(housekeeper);
        if (registry != null) {
            gauges.forEach(registry::remove);
            gauges.clear();
        }
    }

    // An idle wait ends now. A batch in flight is not interrupted: it stops after the send in flight and commits, so
    // its rows are not sent again. The worker checks its own identity, so a late exit cannot run alongside a restart.
    private void await(Thread drainer) {
        if (!inBatch) {
            drainer.interrupt();
        }
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
    }

    private static void joinBriefly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    // Only a database that answers and refuses means the table is missing. An unreachable one is an outage: the
    // service may still start (Flyway, when present, has already reported it) and the drainer keeps trying.
    private void probeTable() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(PROBE)) {
            statement.setQueryTimeout(timeoutSeconds());
            try (ResultSet ignored = statement.executeQuery()) {
                log.debug("outbox table present");
            }
        } catch (SQLException e) {
            if (!isConnectionFailure(e)) {
                throw new OutboxTableMissingException(e);
            }
            log.warn("Could not check the outbox table, the database is unreachable: {}", e.getClass().getName());
        }
    }

    private static boolean isConnectionFailure(SQLException e) {
        return e instanceof SQLTransientConnectionException || e instanceof SQLNonTransientConnectionException
                || e instanceof SQLRecoverableException || (e.getSQLState() != null && e.getSQLState().startsWith("08"));
    }

    private void runWorker() {
        while (worker == Thread.currentThread()) {
            boolean moreDue = false;
            try {
                moreDue = drainBatch() == settings.batchSize() && pause.isZero();
            } catch (SQLException | RuntimeException | LinkageError | AssertionError e) {
                // The database or a bug in a transport, not the sink's answer. The row stays due; the drainer survives,
                // because a service that keeps writing rows with no drainer loses delivery without a sign.
                log.warn("Outbox drain failed: {}", e.getClass().getName());
                increasePause();
            }
            if (!moreDue && worker == Thread.currentThread()) {
                sleep(pause.isZero() ? settings.pollInterval() : pause);
            }
        }
    }

    private void runMaintenance() {
        Instant nextGauges = Instant.MIN;
        Instant nextRetention = Instant.MIN;
        while (maintenance == Thread.currentThread()) {
            Instant now = clock.instant();
            if (!now.isBefore(nextGauges)) {
                nextGauges = now.plus(settings.gaugeInterval());
                maintain("refresh the outbox metrics", this::refreshGauges);
            }
            if (!now.isBefore(nextRetention)) {
                nextRetention = now.plus(settings.retentionInterval());
                maintain("delete sent outbox rows", this::deleteSent);
            }
            sleep(settings.gaugeInterval().compareTo(settings.retentionInterval()) < 0
                    ? settings.gaugeInterval() : settings.retentionInterval());
        }
    }

    private void maintain(String what, MaintenanceTask task) {
        try {
            task.run();
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not {}: {}", what, e.getClass().getName());
        }
    }

    @FunctionalInterface
    private interface MaintenanceTask {
        void run() throws SQLException;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            log.debug("outbox wait interrupted");
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
        inBatch = true;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                List<Runnable> afterCommit = new ArrayList<>();
                List<Row> rows = claim(connection);
                boolean failed = false;
                for (Row row : rows) {
                    if (stopping || !send(connection, row, afterCommit)) {
                        failed = !stopping;
                        break;
                    }
                }
                connection.commit();
                committed = true;
                afterCommit.forEach(Runnable::run);
                if (failed) {
                    increasePause();
                } else if (!rows.isEmpty()) {
                    pause = Duration.ZERO;
                }
                return rows.size();
            } finally {
                if (!committed) {
                    rollback(connection);
                }
            }
        } finally {
            inBatch = false;
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

    // Returns false when the batch should end. A row that cannot be read does not end it: nothing about it can improve.
    private boolean send(Connection connection, Row row, List<Runnable> afterCommit) throws SQLException {
        Map<String, String> stored;
        try {
            stored = parseHeaders(row.headers());
        } catch (JsonProcessingException e) {
            markFailed(connection, row, Map.of(), e.getClass().getName(), row.rejections(), true, afterCommit);
            return true;
        }
        try (OutboxContext.Scope ignored = context.restore(stored)) {
            try {
                byte[] key = row.key() != null ? row.key().getBytes(StandardCharsets.UTF_8) : null;
                transport.send(row.destination(), key, row.payload(), messageHeaders(row, stored));
            } catch (RuntimeException e) {
                boolean rejected = e instanceof EventPublishException publish && publish.rejected();
                int rejections = row.rejections() + (rejected ? 1 : 0);
                markFailed(connection, row, stored, e.getClass().getName(), rejections,
                        rejections >= settings.maxRejections(), afterCommit);
                return false;
            }
            markSent(connection, row);
            afterCommit.add(() -> increment(sentCounter));
            return true;
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

    private static Map<String, String> parseHeaders(String json) throws JsonProcessingException {
        Map<String, String> headers =
                JdbcOutboxPublisher.JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        if (headers == null) {
            throw new com.fasterxml.jackson.core.JsonParseException(null, "outbox headers are null");
        }
        return headers;
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

    private void markFailed(Connection connection, Row row, Map<String, String> stored, String error, int rejections,
            boolean park, List<Runnable> afterCommit) throws SQLException {
        int attempts = row.attempts() + 1;
        try (PreparedStatement statement = connection.prepareStatement(MARK_FAILED)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setString(1, park ? "PARKED" : "PENDING");
            statement.setInt(2, attempts);
            statement.setInt(3, rejections);
            statement.setObject(4, OffsetDateTime.now(clock).plus(settings.backoff(attempts)));
            statement.setString(5, error);
            statement.setString(6, row.eventId());
            statement.executeUpdate();
        }
        // Logged here, inside the restored trace; the parking transition only after it has committed.
        log.warn("Outbox event {} of type {} failed on attempt {}: {}", row.eventId(), row.eventType(), attempts, error);
        afterCommit.add(() -> increment(failedCounter));
        if (park) {
            afterCommit.add(() -> {
                increment(parkedCounter);
                try (OutboxContext.Scope ignored = context.restore(stored)) {
                    log.warn("Outbox event {} of type {} parked ({} rejections): {}", row.eventId(), row.eventType(),
                            rejections, error);
                }
            });
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
        lastGaugeRefresh = clock.instant();
    }

    /**
     * Deletes sent rows past retention, each bounded delete in its own transaction (whatever the pool's auto-commit
     * default). Never touches pending or parked rows.
     *
     * @return how many rows were deleted
     * @throws SQLException if the database failed
     */
    int deleteSent() throws SQLException {
        int total = 0;
        int limit = settings.batchSize() * 20;
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(settings.sentRetention());
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(DELETE_SENT)) {
            connection.setAutoCommit(true);
            statement.setQueryTimeout(timeoutSeconds());
            statement.setObject(1, cutoff);
            statement.setInt(2, limit);
            int deleted;
            do {
                deleted = statement.executeUpdate();
                total += deleted;
            } while (deleted == limit && !stopping);
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
