package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.data.Money;
import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The exception types data-access failures really surface as on Oracle, path by path (scope R4: the
 * platform maps observed types, not guessed ones). Each test records the cause chain it saw, so a
 * changed type in a Spring/Hibernate/driver upgrade fails here first. Not transactional: every path
 * commits or fails for real. Excluded from the default build (docker JUnit tag); run under
 * {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=1000"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OracleExceptionPathsIT {

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private OracleWidgetRepository widgets;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clean() {
        tx().executeWithoutResult(status -> {
            em.createNativeQuery("delete from oracle_part").executeUpdate();
            em.createNativeQuery("delete from oracle_widget").executeUpdate();
        });
    }

    @Test
    void duplicateKeyFlushedAtCommitIsTranslated() {
        widgets.saveAndFlush(widget("DUP-1"));
        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> widgets.save(widget("DUP-1"))));
        // Hibernate batches the insert (batch_size 50), so the driver reports a BatchUpdateException, not
        // an SQLIntegrityConstraintViolationException; only its SQLState (23000) says "integrity".
        assertThat(chain(thrown)).containsExactly("org.springframework.dao.DataIntegrityViolationException",
                "org.hibernate.exception.ConstraintViolationException", "java.sql.BatchUpdateException");
        assertThat(sqlState(thrown)).isEqualTo("23000");
    }

    @Test
    void duplicateKeyThroughSaveAndFlushIsTranslated() {
        widgets.saveAndFlush(widget("DUP-2"));
        Throwable thrown = catchThrowable(() -> widgets.saveAndFlush(widget("DUP-2")));
        assertThat(chain(thrown)).startsWith("org.springframework.dao.DataIntegrityViolationException");
    }

    @Test
    void duplicateKeyThroughTheEntityManagerDirectlyIsNotTranslated() {
        widgets.saveAndFlush(widget("DUP-3"));
        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> {
            em.persist(widget("DUP-3"));
            em.flush();
        }));
        // No @Repository proxy, so no Spring translation: the raw Hibernate type reaches the caller.
        assertThat(chain(thrown)).containsExactly("org.hibernate.exception.ConstraintViolationException",
                "java.sql.BatchUpdateException");
        assertThat(sqlState(thrown)).isEqualTo("23000");
    }

    @Test
    void foreignKeyAndCheckViolationsAreIntegrityViolations() {
        Throwable foreignKey = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
                "insert into oracle_part (id, widget_id, name) values (1, 999999, 'orphan')").executeUpdate()));
        assertThat(chain(foreignKey)).contains("java.sql.SQLIntegrityConstraintViolationException");
        assertThat(foreignKey).hasStackTraceContaining("ORA-02291");

        OracleWidget saved = widgets.saveAndFlush(widget("CHK-1"));
        Throwable check = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
                "update oracle_widget set active = 2 where id = " + saved.id).executeUpdate()));
        assertThat(chain(check)).contains("java.sql.SQLIntegrityConstraintViolationException");
        assertThat(check).hasStackTraceContaining("ORA-02290");
    }

    @Test
    void concurrentUpdateIsAnOptimisticLockingFailure() {
        OracleWidget saved = widgets.saveAndFlush(widget("OPT-1"));
        OracleWidget first = widgets.findById(saved.id).orElseThrow();
        OracleWidget second = widgets.findById(saved.id).orElseThrow();
        first.active = true;
        widgets.saveAndFlush(first);
        second.active = false;
        Throwable thrown = catchThrowable(() -> widgets.saveAndFlush(second));
        assertThat(chain(thrown)).startsWith("org.springframework.orm.ObjectOptimisticLockingFailureException");
    }

    @Test
    void lockHeldElsewhereIsAPessimisticLockingFailure() throws Exception {
        OracleWidget saved = widgets.saveAndFlush(widget("PES-1"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> tx().executeWithoutResult(status -> {
            em.find(OracleWidget.class, saved.id, LockModeType.PESSIMISTIC_WRITE);
            locked.countDown();
            await(release);
        }));
        holder.start();
        try {
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> em.find(OracleWidget.class,
                    saved.id, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0))));
            // EntityManager directly: the JPA type, untranslated (ORA-00054 resource busy, NOWAIT).
            assertThat(chain(thrown)).startsWith("jakarta.persistence.LockTimeoutException",
                    "org.hibernate.exception.LockTimeoutException");
            assertThat(thrown).hasStackTraceContaining("ORA-00054");
        } finally {
            release.countDown();
            holder.join(30_000);
        }
    }

    @Test
    void selectOverItsQueryTimeoutIsCancelled() {
        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
                        "select count(*) from (select level l from dual connect by level <= 20000) a,"
                                + " (select level l from dual connect by level <= 20000) b")
                .setHint("jakarta.persistence.query.timeout", 1000)
                .getSingleResult()));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        // EntityManager directly: the JPA type, untranslated.
        assertThat(chain(thrown)).startsWith("jakarta.persistence.QueryTimeoutException");
        assertThat(thrown).hasStackTraceContaining("ORA-01013");
        assertThat(millis).isBetween(900L, 4_000L);
    }

    @Test
    void aWaitingPlSqlCallIsNotInterruptedByTheQueryTimeout() {
        // Recorded behaviour, not a wish: a PL/SQL wait (like a lock wait or a dead network) ignores the
        // cancel, so the statement timeout cannot be the only guard (scope R2.3, D110: read timeout).
        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status -> em.createNativeQuery(
                        "select pdt_sleep(3) from dual")
                .setHint("jakarta.persistence.query.timeout", 1000)
                .getSingleResult()));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(thrown).isNull();
        assertThat(millis).isGreaterThanOrEqualTo(2_900L);
    }

    @Test
    void exhaustedPoolFailsTransactionBeginWithinTheConnectionTimeout() throws Exception {
        CountDownLatch held = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread holder = new Thread(() -> tx().executeWithoutResult(status -> {
                em.createNativeQuery("select 1 from dual").getSingleResult();
                held.countDown();
                await(release);
            }));
            holders.add(holder);
            holder.start();
        }
        try {
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            Throwable thrown = catchThrowable(() -> tx().executeWithoutResult(status ->
                    em.createNativeQuery("select 1 from dual").getSingleResult()));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(chain(thrown)).containsExactly("org.springframework.transaction.CannotCreateTransactionException",
                    "org.hibernate.exception.JDBCConnectionException", "java.sql.SQLTransientConnectionException");
            assertThat(millis).isBetween(900L, 5_000L);
        } finally {
            release.countDown();
            for (Thread holder : holders) {
                holder.join(30_000);
            }
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static OracleWidget widget(String reference) {
        OracleWidget widget = new OracleWidget();
        widget.referenceCode = reference;
        widget.price = Money.of("1.00", "AED");
        return widget;
    }

    /** Class names from the thrown exception down to the root cause. */
    private static List<String> chain(Throwable thrown) {
        assertThat(thrown).as("the path must fail").isNotNull();
        List<String> types = new ArrayList<>();
        for (Throwable t = thrown; t != null && types.size() < 20; t = t.getCause()) {
            types.add(t.getClass().getName());
        }
        System.out.println("observed chain: " + types);
        return types;
    }

    /** The first SQLState in the cause chain (BatchUpdateException included). */
    private static String sqlState(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
