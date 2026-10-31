package ae.gov.dubaicustoms.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublishException;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxTestSupport.RecordingTransport;
import ae.gov.dubaicustoms.platform.messaging.outbox.OutboxTestSupport.Sent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.oracle.OracleContainer;

/**
 * The outbox on Oracle (23ai Free standing in for 19c, HD13), with the shipped DDL, as a runtime user that holds only
 * a role with DML grants and reaches the owner's table through {@code CURRENT_SCHEMA}, as a real deployment does.
 * Reuses the warm container with {@code testcontainers.reuse.enable=true}: a cold start takes minutes.
 */
@Tag("docker")
class OutboxOracleIT {

    private static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(Duration.ofMinutes(15)).withReuse(true);
    private static final Clock CLOCK = Clock.systemUTC();

    private static String owner;
    private static String runtime;
    private static String password;

    private DriverManagerDataSource runtimeSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeAll
    static void createSchema() throws Exception {
        ORACLE.start();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        owner = "OBX_O" + suffix;
        runtime = "OBX_R" + suffix;
        String role = "OBX_ROLE" + suffix;
        password = "Pw" + suffix;
        try (Connection system = DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
                Statement statement = system.createStatement()) {
            statement.execute("create user " + owner + " identified by \"" + password + "\" quota unlimited on users");
            statement.execute("grant create session, create table to " + owner);
            statement.execute("create role " + role);
            statement.execute("create user " + runtime + " identified by \"" + password + "\"");
            statement.execute("grant create session to " + runtime);
            statement.execute("grant " + role + " to " + runtime);
        }
        try (Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), owner, password);
                Statement statement = connection.createStatement()) {
            for (String ddl : OutboxTestSupport.ddl()) {
                statement.execute(ddl);
            }
            statement.execute("create table business (id varchar(64) primary key)");
            statement.execute("grant select, insert, update, delete on platform_outbox to " + role);
            statement.execute("grant select, insert on business to " + role);
        }
    }

    @BeforeEach
    void connect() {
        runtimeSource = new DriverManagerDataSource(ORACLE.getJdbcUrl(), runtime, password);
        runtimeSource.setConnectionProperties(new java.util.Properties());
        var schemaSource = new org.springframework.jdbc.datasource.DelegatingDataSource(runtimeSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (Statement statement = connection.createStatement()) {
                    statement.execute("alter session set current_schema = " + owner);
                }
                return connection;
            }
        };
        source = schemaSource;
        jdbc = new JdbcTemplate(schemaSource);
        jdbc.update("delete from platform_outbox");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(schemaSource));
    }

    private javax.sql.DataSource source;

    private JdbcOutboxPublisher publisher() {
        return new JdbcOutboxPublisher(source, OutboxTestSupport.TEXT, OutboxContext.NONE, CLOCK);
    }

    private OutboxDrainer drainer(RecordingTransport transport, int batchSize, int maxRejections) {
        return new OutboxDrainer(source, transport, OutboxContext.NONE, OutboxTestSupport.settings(batchSize, maxRejections),
                CLOCK, null);
    }

    private void publish(int count) {
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                publisher().publish("dc.orders", EventEnvelope.of("event-" + i).build());
            }
        });
    }

    @Test
    void twoConcurrentDrainersEachClaimAFullBatchOfDifferentRows() throws Exception {
        publish(25);
        // Each transport holds its first send until the other drainer has claimed too, so both hold locks at once.
        CountDownLatch bothClaimed = new CountDownLatch(2);
        RecordingTransport first = holdingTransport(bothClaimed);
        RecordingTransport second = holdingTransport(bothClaimed);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> drainer(first, 10, 3).drainBatch());
            Future<Integer> b = executor.submit(() -> drainer(second, 10, 3).drainBatch());

            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(10);
            assertThat(b.get(60, TimeUnit.SECONDS)).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }

        List<String> firstIds = first.sent.stream().map(Sent::eventId).toList();
        List<String> secondIds = second.sent.stream().map(Sent::eventId).toList();
        assertThat(firstIds).hasSize(10).doesNotContainAnyElementsOf(secondIds);
        assertThat(secondIds).hasSize(10);
        assertThat(jdbc.queryForObject("select count(*) from platform_outbox where status = 'PENDING'", Integer.class))
                .isEqualTo(5);
    }

    private static RecordingTransport holdingTransport(CountDownLatch bothClaimed) {
        RecordingTransport transport = new RecordingTransport();
        transport.onSend = destination -> {
            bothClaimed.countDown();
            try {
                assertThat(bothClaimed.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        return transport;
    }

    @Test
    void largeRowsDoNotWidenTheLocksAtEitherBatchSize() throws Exception {
        // A claimer must leave the rest of the table claimable: the fetch is the lock boundary, and LOB rows must not
        // change that. Measured directly: while one holds a batch, the other can still claim what is left.
        char[] big = new char[200_000];
        Arrays.fill(big, 'x');
        String payload = new String(big);
        for (int batchSize : new int[] {1, 7}) {
            jdbc.update("delete from platform_outbox");
            transaction.executeWithoutResult(status -> {
                for (int i = 0; i < 20; i++) {
                    publisher().publish("dc.orders", EventEnvelope.of(payload).build());
                }
            });
            CountDownLatch bothClaimed = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = executor.submit(() -> drainer(holdingTransport(bothClaimed), batchSize, 3).drainBatch());
                Future<Integer> b = executor.submit(() -> drainer(holdingTransport(bothClaimed), batchSize, 3).drainBatch());

                assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(batchSize);
                assertThat(b.get(60, TimeUnit.SECONDS)).isEqualTo(batchSize);
            } finally {
                executor.shutdownNow();
            }
            assertThat(jdbc.queryForObject("select count(*) from platform_outbox where status = 'PENDING'", Integer.class))
                    .isEqualTo(20 - 2 * batchSize);
        }
    }

    @Test
    void aBusinessConstraintFailingAtCommitLeavesNoRow() {
        // A deferred constraint fails at commit, after the row was written in the same transaction.
        String table = "deferred_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(new DriverManagerDataSource(ORACLE.getJdbcUrl(), owner, password)).execute(
                "create table " + table + " (id number constraint " + table + "_nn check (id > 0) deferrable initially deferred)");
        new JdbcTemplate(new DriverManagerDataSource(ORACLE.getJdbcUrl(), owner, password)).execute(
                "grant insert on " + table + " to " + runtime);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            publisher().publish("dc.orders", EventEnvelope.of("x").build());
            jdbc.update("insert into " + table + " values (-1)");
        })).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("select count(*) from platform_outbox", Integer.class)).isZero();
    }

    @Test
    void aDrainerThatDiesWithoutCommittingLeavesItsRowsDueWithTheSameIds() throws Exception {
        // What a killed pod looks like to the database: the transaction ends without a commit.
        publish(3);
        RecordingTransport dying = new RecordingTransport();
        drainInATransactionThatRollsBack(dying);
        assertThat(jdbc.queryForObject("select count(*) from platform_outbox where status = 'PENDING'", Integer.class))
                .isEqualTo(3);

        RecordingTransport next = new RecordingTransport();
        assertThat(drainer(next, 10, 3).drainBatch()).isEqualTo(3);

        assertThat(next.sent.stream().map(Sent::eventId).toList())
                .containsExactlyInAnyOrderElementsOf(dying.sent.stream().map(Sent::eventId).toList());
    }

    private void drainInATransactionThatRollsBack(RecordingTransport transport) throws Exception {
        // The commit fails because the connection is gone; the sends have already happened.
        var dying = new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("commit")) {
                                connection.rollback();
                                throw new SQLException("connection lost");
                            }
                            try {
                                return method.invoke(connection, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
        var drainer = new OutboxDrainer(dying, transport, OutboxContext.NONE, OutboxTestSupport.settings(10, 3), CLOCK, null);
        try {
            drainer.drainBatch();
        } catch (SQLException expected) {
            assertThat(expected.getMessage()).contains("connection lost");
        }
        assertThat(transport.sent).hasSize(3);
    }

    @Test
    void aBusinessRollbackLeavesNoRowOnOracle() {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.update("insert into business values (?)", UUID.randomUUID().toString());
                publisher().publish("dc.orders", EventEnvelope.of("x").build());
                throw new IllegalArgumentException("business failure");
            });
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("business failure");
        }

        assertThat(jdbc.queryForObject("select count(*) from platform_outbox", Integer.class)).isZero();
    }

    @Test
    void aLargePayloadAndHeadersRoundTrip() throws Exception {
        // Past the 32 KB where a driver binds a LOB differently.
        char[] big = new char[1_000_000];
        Arrays.fill(big, 'x');
        String payload = new String(big);
        char[] longHeader = new char[100_000];
        Arrays.fill(longHeader, 'h');
        transaction.executeWithoutResult(status -> publisher().publish("dc.orders",
                EventEnvelope.of(payload).header("big", new String(longHeader)).build()));
        RecordingTransport transport = new RecordingTransport();

        drainer(transport, 10, 3).drainBatch();

        assertThat(new String(transport.sent.get(0).value(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(payload);
        assertThat(transport.sent.get(0).headers().get("big")).hasSize(100_000);
    }

    @Test
    void parkingAndRetentionWorkOnOracleAsTheRuntimeUser() throws Exception {
        publish(1);
        RecordingTransport rejecting = new RecordingTransport();
        rejecting.onSend = destination -> {
            throw new EventPublishException("unroutable", null, true);
        };
        OutboxDrainer drainer = drainer(rejecting, 10, 1);

        drainer.drainBatch();

        assertThat(jdbc.queryForObject("select status from platform_outbox", String.class)).isEqualTo("PARKED");
        drainer.refreshGauges();

        publish(1);
        RecordingTransport accepting = new RecordingTransport();
        OutboxDrainer retention = drainer(accepting, 10, 3);
        retention.drainBatch();
        jdbc.update("update platform_outbox set sent_at = ? where status = 'SENT'",
                Timestamp.from(Instant.now().minus(Duration.ofDays(2))));

        assertThat(retention.deleteSent()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select status from platform_outbox", String.class)).isEqualTo("PARKED");
    }

    @Test
    void theDrainerStartsWhenTheRuntimeUserCanReadTheTable() {
        OutboxDrainer drainer = drainer(new RecordingTransport(), 10, 3);

        drainer.start();

        assertThat(drainer.isRunning()).isTrue();
        drainer.stop();
    }
}
