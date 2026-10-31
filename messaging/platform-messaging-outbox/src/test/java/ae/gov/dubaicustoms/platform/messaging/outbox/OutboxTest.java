package ae.gov.dubaicustoms.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxTestSupport.RecordingTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Publisher and drainer against H2, with the shipped DDL. Oracle behaviour is in {@code OutboxOracleIT}. */
class OutboxTest {

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private final RecordingTransport transport = new RecordingTransport();
    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void createTable() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        for (String statement : OutboxTestSupport.ddl()) {
            jdbc.execute(statement);
        }
        jdbc.execute("create table business (id varchar(64) primary key)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private JdbcOutboxPublisher publisher(OutboxContext context) {
        return new JdbcOutboxPublisher(dataSource, OutboxTestSupport.TEXT, context, clock);
    }

    private OutboxDrainer drainer(OutboxSettings settings) {
        return new OutboxDrainer(dataSource, transport, OutboxContext.NONE, settings, clock, null);
    }

    private EventEnvelope<String> publishOne() {
        EventEnvelope<String> event = EventEnvelope.of("hello").key("k").build();
        transaction.executeWithoutResult(status -> publisher(OutboxContext.NONE).publish("dc.orders", event));
        return event;
    }

    private String status(EventEnvelope<?> event) {
        return jdbc.queryForObject("select status from platform_outbox where event_id = ?", String.class, event.eventId());
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from platform_outbox", Integer.class);
    }

    @Test
    void theRowCommitsWithTheBusinessWrite() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("insert into business values ('b1')");
            publisher(OutboxContext.NONE).publish("dc.orders", EventEnvelope.of("hello").build());
        });

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void aBusinessRollbackLeavesNoRow() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("insert into business values ('b1')");
            publisher(OutboxContext.NONE).publish("dc.orders", EventEnvelope.of("hello").build());
            throw new IllegalArgumentException("business failure");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(rows()).isZero();
    }

    @Test
    void publishRefusesWithoutATransaction() {
        assertThatThrownBy(() -> publisher(OutboxContext.NONE).publish("dc.orders", EventEnvelope.of("hello").build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("uncommitted transaction");
    }

    @Test
    void publishRefusesATransactionOnAnotherDataSource() {
        // It would auto-commit on its own connection, and survive the business rollback.
        var other = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var otherTransaction = new TransactionTemplate(new DataSourceTransactionManager(other));

        assertThatThrownBy(() -> otherTransaction.executeWithoutResult(status ->
                publisher(OutboxContext.NONE).publish("dc.orders", EventEnvelope.of("hello").build())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DataSource");
        assertThat(rows()).isZero();
    }

    @Test
    void aConnectionOnlySynchronizedUnderAnotherDatabasesTransactionIsRefused() {
        // Reading through the outbox's database binds its connection, auto-committing, under the other transaction.
        var other = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var otherTransaction = new TransactionTemplate(new DataSourceTransactionManager(other));

        assertThatThrownBy(() -> otherTransaction.executeWithoutResult(status -> {
            jdbc.queryForObject("select count(*) from business", Integer.class);
            publisher(OutboxContext.NONE).publish("dc.orders", EventEnvelope.of("hello").build());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void aTransactionAwareProxyStillFindsTheTransactionsConnection() {
        var proxied = new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(dataSource);
        var publisher = new JdbcOutboxPublisher(proxied, OutboxTestSupport.TEXT, OutboxContext.NONE, clock);

        transaction.executeWithoutResult(status -> publisher.publish("dc.orders", EventEnvelope.of("hello").build()));

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void publishRefusesAnywhereUnderAnAfterCommitCallback() {
        var publisher = publisher(OutboxContext.NONE);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var inJoined = new java.util.concurrent.atomic.AtomicBoolean(true);

        transaction.executeWithoutResult(status -> {
            jdbc.update("insert into business values ('b1')");
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // Nothing commits this row: without the check it would vanish silently.
                            assertThat(publisher.canPublish()).isFalse();
                            try {
                                publisher.publish("dc.orders", EventEnvelope.of("lost").build());
                            } catch (IllegalStateException e) {
                                failure.set(e);
                            }
                            // A REQUIRED method here joins the finished transaction: it must not look like a new one.
                            transaction.executeWithoutResult(inner -> inJoined.set(publisher.canPublish()));
                        }
                    });
        });

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(inJoined).isFalse();
        assertThat(rows()).isZero();
    }

    @Test
    void anInjectedHeaderOtherThanTheTraceNeverReachesARow() {
        OutboxContext propagating = new OutboxContext() {
            @Override
            public Map<String, String> traceHeaders() {
                return Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                        "authorization", "Bearer secret", "baggage", "user=alice");
            }

            @Override
            public String principal() {
                return null;
            }

            @Override
            public Scope restore(Map<String, String> headers) {
                return () -> { };
            }
        };
        transaction.executeWithoutResult(status ->
                publisher(propagating).publish("dc.orders", EventEnvelope.of("hello").build()));

        assertThat(jdbc.queryForObject("select headers from platform_outbox", String.class))
                .contains("traceparent").doesNotContain("authorization").doesNotContain("baggage");
    }

    @Test
    void aCredentialNamedHeaderIsRefused() {
        for (String name : List.of("Authorization", "x-auth-token", "db_password", "pwd", "sessionCookie", "API_KEY")) {
            EventEnvelope<String> event = EventEnvelope.of("hello").header(name, "value").build();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    publisher(OutboxContext.NONE).publish("dc.orders", event)))
                    .as(name).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        }
        assertThat(rows()).isZero();
    }

    @Test
    void theRowStoresTraceAndPrincipalAndTheMessageCarriesThem() throws Exception {
        OutboxContext context = new OutboxContext() {
            @Override
            public Map<String, String> traceHeaders() {
                return Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
            }

            @Override
            public String principal() {
                return "user-1";
            }

            @Override
            public Scope restore(Map<String, String> headers) {
                restored.add(headers.get("traceparent"));
                return () -> restored.add("closed");
            }
        };
        EventEnvelope<String> event = EventEnvelope.of("hello").key("k").header("tenant", "t1").build();
        transaction.executeWithoutResult(status -> publisher(context).publish("dc.orders:placed", event));

        new OutboxDrainer(dataSource, transport, context, OutboxTestSupport.settings(10, 3), clock, null).drainBatch();

        assertThat(transport.sent).hasSize(1);
        var sent = transport.sent.get(0);
        assertThat(sent.destination()).isEqualTo("dc.orders:placed");
        assertThat(new String(sent.key(), StandardCharsets.UTF_8)).isEqualTo("k");
        assertThat(new String(sent.value(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(sent.headers())
                .containsEntry("eventId", event.eventId())
                .containsEntry("eventType", "String")
                .containsEntry("eventVersion", "1")
                .containsEntry("contentType", "text/plain")
                .containsEntry("tenant", "t1")
                .containsEntry("principal", "user-1")
                .containsEntry("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(restored).containsExactly("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "closed");
    }

    private final java.util.List<String> restored = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Test
    void aSentRowIsMarkedAndNotSentAgain() throws Exception {
        EventEnvelope<String> event = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 3));

        assertThat(drainer.drainBatch()).isEqualTo(1);
        assertThat(drainer.drainBatch()).isZero();

        assertThat(transport.sent).hasSize(1);
        assertThat(status(event)).isEqualTo("SENT");
    }

    @Test
    void anOutageNeverParksARow() throws Exception {
        // A long outage must not park healthy events: recovery delivers the backlog.
        EventEnvelope<String> event = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 2));
        transport.onSend = destination -> {
            throw new EventPublishException("broker down", null);
        };

        for (int i = 0; i < 5; i++) {
            drainer.drainBatch();
        }
        assertThat(status(event)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from platform_outbox", Integer.class)).isEqualTo(5);

        transport.onSend = destination -> { };
        drainer.drainBatch();

        assertThat(status(event)).isEqualTo("SENT");
        assertThat(transport.sent).hasSize(1);
    }

    @Test
    void repeatedRejectionsParkARowAndReplayKeepsItsId() throws Exception {
        EventEnvelope<String> event = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 2));
        transport.onSend = destination -> {
            throw new EventPublishException("unroutable secret-host:5672", null, true);
        };

        drainer.drainBatch();
        assertThat(status(event)).isEqualTo("PENDING");
        drainer.drainBatch();
        assertThat(status(event)).isEqualTo("PARKED");
        assertThat(drainer.drainBatch()).isZero();

        // The stored error names the class only: a transport message can carry hosts and credentials.
        assertThat(jdbc.queryForObject("select last_error from platform_outbox", String.class))
                .isEqualTo(EventPublishException.class.getName());

        // The documented replay statement.
        jdbc.update("update platform_outbox set status = 'PENDING', rejections = 0, next_attempt_at = ? "
                + "where status = 'PARKED'", Timestamp.from(Instant.now()));
        transport.onSend = destination -> { };
        drainer.drainBatch();

        assertThat(status(event)).isEqualTo("SENT");
        assertThat(transport.sent).extracting(OutboxTestSupport.Sent::eventId).containsExactly(event.eventId());
    }

    @Test
    void aFailureEndsTheBatchButTheFailedRowDoesNotBlockTheOthers() throws Exception {
        // The first row's backoff moves it behind the others, so one bad destination cannot hold up the table.
        var settings = new OutboxSettings(10, Duration.ofMillis(20), 5, Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofDays(1), Duration.ofDays(1),
                Duration.ofSeconds(10));
        EventEnvelope<String> bad = EventEnvelope.of("bad").build();
        transaction.executeWithoutResult(status -> publisher(OutboxContext.NONE).publish("dc.bad", bad));
        EventEnvelope<String> good = publishOne();
        OutboxDrainer drainer = drainer(settings);
        transport.onSend = destination -> {
            if (destination.equals("dc.bad")) {
                throw new EventPublishException("unroutable", null, true);
            }
        };

        drainer.drainBatch();
        assertThat(transport.sent).isEmpty();

        drainer.drainBatch();

        assertThat(status(bad)).isEqualTo("PENDING");
        assertThat(status(good)).isEqualTo("SENT");
    }

    @Test
    void aRowWithUnreadableHeadersIsParkedAndDoesNotStopTheOthers() throws Exception {
        EventEnvelope<String> corrupt = publishOne();
        EventEnvelope<String> good = publishOne();
        jdbc.update("update platform_outbox set headers = 'not json' where event_id = ?", corrupt.eventId());

        drainer(OutboxTestSupport.settings(10, 3)).drainBatch();

        assertThat(status(corrupt)).isEqualTo("PARKED");
        assertThat(status(good)).isEqualTo("SENT");
        assertThat(transport.sent).extracting(OutboxTestSupport.Sent::eventId).containsExactly(good.eventId());
    }

    @Test
    void anErrorFromATransportRollsTheBatchBackAndTheDrainerSurvives() {
        // A worker that dies leaves a service writing rows nobody delivers.
        EventEnvelope<String> event = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 3));
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        transport.onSend = destination -> {
            if (failures.incrementAndGet() == 1) {
                throw new AssertionError("a bug in a transport");
            }
        };

        drainer.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(event)).isEqualTo("SENT"));
        drainer.stop();
    }

    @Test
    void aStopEndsTheBatchAfterTheSendInFlightAndCommitsIt() throws Exception {
        for (int i = 0; i < 5; i++) {
            publishOne();
        }
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(5, 3));
        var sending = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        transport.onSend = destination -> {
            sending.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        drainer.start();
        assertThat(sending.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Thread stopper = new Thread(drainer::stop);
        stopper.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !drainer.isRunning());
        release.countDown();
        stopper.join(10_000);

        // One was in flight when the stop arrived: it is recorded; the other four stay due, unsent.
        assertThat(transport.sent).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from platform_outbox where status = 'SENT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from platform_outbox where status = 'PENDING'", Integer.class)).isEqualTo(4);
    }

    @Test
    void aSecondStartDoesNotAddAWorkerAndARestartWorks() {
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 3));
        drainer.start();
        drainer.start();
        drainer.stop();
        EventEnvelope<String> event = publishOne();

        drainer.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(event)).isEqualTo("SENT"));
        drainer.stop();

        assertThat(transport.sent).hasSize(1);
    }

    @Test
    void aRestartWaitsForABatchThatIgnoredTheStop() throws Exception {
        publishOne();
        var settings = new OutboxSettings(5, Duration.ofMillis(20), 3, Duration.ZERO, Duration.ZERO, Duration.ofHours(24),
                Duration.ofSeconds(30), Duration.ofDays(1), Duration.ofDays(1), Duration.ofMillis(50));
        OutboxDrainer drainer = drainer(settings);
        var sending = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        transport.onSend = destination -> {
            sending.countDown();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException e) {
                    // A send that ignores interruption, like a blocked socket write.
                }
            }
        };
        drainer.start();
        assertThat(sending.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        drainer.stop();

        assertThatThrownBy(drainer::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("not finished");

        release.countDown();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            drainer.start();
            assertThat(drainer.isRunning()).isTrue();
        });
        drainer.stop();
    }

    @Test
    void settingsThatWouldSilentlyRemoveABoundAreRefused() {
        Duration ok = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new OutboxSettings(1, ok, 1, ok, ok, ok, Duration.ofMillis(500), ok, ok, ok))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("statement timeout");
        assertThatThrownBy(() -> new OutboxSettings(1, ok, 1, ok, ok, ok, ok, ok, ok, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stop timeout");
        assertThatThrownBy(() -> new OutboxSettings(1, Duration.ZERO, 1, ok, ok, ok, ok, ok, ok, ok))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poll interval");
        assertThatThrownBy(() -> new OutboxSettings(1, ok, 1, Duration.ofSeconds(-1), ok, ok, ok, ok, ok, ok))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("backoffs");
    }

    @Test
    void retentionCommitsEvenOnAPoolThatDefaultsToNoAutoCommit() throws Exception {
        EventEnvelope<String> old = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 3));
        drainer.drainBatch();
        jdbc.update("update platform_outbox set sent_at = ? where event_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(2))), old.eventId());
        var noAutoCommit = new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection = super.getConnection();
                connection.setAutoCommit(false);
                return connection;
            }
        };
        var retention = new OutboxDrainer(noAutoCommit, transport, OutboxContext.NONE, OutboxTestSupport.settings(10, 3), clock, null);

        assertThat(retention.deleteSent()).isEqualTo(1);

        assertThat(rows()).isZero();
    }

    @Test
    void parkingIsAnnouncedOnlyAfterItCommits() throws Exception {
        // A commit that fails leaves the row pending: no parked metric may have fired.
        publishOne();
        var registry = new SimpleMeterRegistry();
        var failingCommit = new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection = super.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("commit")) {
                                throw new java.sql.SQLException("connection lost");
                            }
                            return method.invoke(connection, args);
                        });
            }
        };
        var drainer = new OutboxDrainer(failingCommit, transport, OutboxContext.NONE, OutboxTestSupport.settings(10, 1), clock, registry);
        transport.onSend = destination -> {
            throw new EventPublishException("rejected", null, true);
        };

        assertThatThrownBy(drainer::drainBatch).isInstanceOf(java.sql.SQLException.class);

        assertThat(registry.get("platform.outbox.parked.total").counter().count()).isZero();
        assertThat(jdbc.queryForObject("select status from platform_outbox", String.class)).isEqualTo("PENDING");
    }

    @Test
    void aBatchIsBounded() throws Exception {
        for (int i = 0; i < 5; i++) {
            publishOne();
        }

        assertThat(drainer(OutboxTestSupport.settings(2, 3)).drainBatch()).isEqualTo(2);

        assertThat(transport.sent).hasSize(2);
    }

    @Test
    void retentionDeletesOnlyOldSentRows() throws Exception {
        EventEnvelope<String> old = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 1));
        drainer.drainBatch();
        jdbc.update("update platform_outbox set sent_at = ? where event_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(2))), old.eventId());
        EventEnvelope<String> recent = publishOne();
        drainer.drainBatch();
        EventEnvelope<String> pending = publishOne();
        EventEnvelope<String> parked = publishOne();
        jdbc.update("update platform_outbox set status = 'PARKED', sent_at = ? where event_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(2))), parked.eventId());

        assertThat(drainer.deleteSent()).isEqualTo(1);

        assertThat(jdbc.queryForList("select event_id from platform_outbox", String.class))
                .containsExactlyInAnyOrder(recent.eventId(), pending.eventId(), parked.eventId());
    }

    @Test
    void metricsReportTheBacklog() throws Exception {
        var registry = new SimpleMeterRegistry();
        var drainer = new OutboxDrainer(dataSource, transport, OutboxContext.NONE, OutboxTestSupport.settings(1, 1), clock,
                registry);
        publishOne();
        publishOne();
        publishOne();
        jdbc.update("update platform_outbox set created_at = ?", Timestamp.from(Instant.now().minus(Duration.ofHours(1))));

        drainer.drainBatch();
        transport.onSend = destination -> {
            throw new EventPublishException("rejected", null, true);
        };
        drainer.drainBatch();
        drainer.refreshGauges();

        assertThat(registry.get("platform.outbox.pending").gauge().value()).isEqualTo(1);
        assertThat(registry.get("platform.outbox.parked").gauge().value()).isEqualTo(1);
        assertThat(registry.get("platform.outbox.oldest.pending.age").gauge().value()).isGreaterThan(3000);
        assertThat(registry.get("platform.outbox.sent").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.outbox.failed").counter().count()).isEqualTo(1);
        assertThat(registry.get("platform.outbox.parked.total").counter().count()).isEqualTo(1);
        // Bounded tags: no event ids, no destinations.
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
        assertThat(registry.get("platform.outbox.gauges.age").gauge().value()).isLessThan(60);
    }

    @Test
    void aMissingTableFailsTheStart() {
        jdbc.execute("drop table platform_outbox");

        assertThatThrownBy(() -> drainer(OutboxTestSupport.settings(10, 3)).start())
                .isInstanceOf(OutboxTableMissingException.class).hasMessageContaining("platform_outbox");
    }

    @Test
    void anUnreachableDatabaseAtStartIsAnOutageNotAMissingTable() {
        // The drainer keeps trying; "table missing" would send an operator looking in the wrong place.
        var unreachable = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                throw new java.sql.SQLTransientConnectionException("pool timed out");
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
                return getConnection();
            }
        };
        var drainer = new OutboxDrainer(unreachable, transport, OutboxContext.NONE, OutboxTestSupport.settings(10, 3), clock,
                null);

        drainer.start();

        assertThat(drainer.isRunning()).isTrue();
        drainer.stop();
    }

    @Test
    void theLifecycleDrainsAndStops() {
        EventEnvelope<String> event = publishOne();
        OutboxDrainer drainer = drainer(OutboxTestSupport.settings(10, 3));

        drainer.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(status(event)).isEqualTo("SENT"));
        assertThat(drainer.isRunning()).isTrue();
        drainer.stop();

        assertThat(drainer.isRunning()).isFalse();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        var settings = new OutboxSettings(1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1));

        assertThat(settings.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(settings.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(settings.backoff(4)).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.backoff(500)).isEqualTo(Duration.ofSeconds(5));
    }
}
