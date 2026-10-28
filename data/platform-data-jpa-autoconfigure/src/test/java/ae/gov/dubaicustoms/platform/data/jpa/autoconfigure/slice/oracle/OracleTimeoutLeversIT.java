package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which timeout lever really bounds a slow Oracle statement (scope R2.3, D110): each test runs the
 * same SQL-engine work (tens of seconds uncancelled) under one lever: each cancels it (ORA-01013) about
 * a second after the 1 s limit (the driver's timeout thread polls once a second). A {@code pdt_sleep}
 * (PL/SQL wait) is NOT interruptible by any of them, like a lock wait or a dead network: that is what the
 * driver read timeout is for. JPA paths surface an untranslated {@code jakarta.persistence.QueryTimeoutException}.
 * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OracleTimeoutLeversIT {

    /** SQL-engine work (interruptible), tens of seconds uncancelled. */
    private static final String HEAVY_SQL =
            "select count(*) from (select level l from dual connect by level <= 20000) a, (select level l from dual connect by level <= 20000) b";

    private static final String HEAVY_JPQL =
            "select count(w) from OracleWidget w where exists (select 1 from OracleNote n) or function('pdt_heavy') = 1";

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void jdbcQueryTimeoutCancelsTheStatement() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(1);
        Outcome outcome = measure(() -> jdbc.queryForObject(HEAVY_SQL, Integer.class));
        assertThat(outcome.cancelled()).as(outcome.toString()).isTrue();
        assertThat(outcome.millis()).isLessThan(4_000);
    }

    @Test
    void queryTimeoutHintOnAJpqlQuery() {
        Outcome outcome = measure(() -> new TransactionTemplate(transactionManager).execute(status -> em.createQuery(
                        HEAVY_JPQL)
                .setHint("jakarta.persistence.query.timeout", 1000)
                .getSingleResult()));
        assertCancelledAsJpaTimeout(outcome);
    }

    @Test
    void transactionTimeoutWithANativeQuery() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setTimeout(1);
        Outcome outcome = measure(() -> tx.execute(status ->
                em.createNativeQuery(HEAVY_SQL).getSingleResult()));
        assertCancelledAsJpaTimeout(outcome);
    }

    @Test
    void transactionTimeoutWithAJpqlQuery() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setTimeout(1);
        Outcome outcome = measure(() -> tx.execute(status -> em.createQuery(
                HEAVY_JPQL).getSingleResult()));
        assertCancelledAsJpaTimeout(outcome);
    }

    private static void assertCancelledAsJpaTimeout(Outcome outcome) {
        assertThat(outcome.cancelled()).as(outcome.toString()).isTrue();
        assertThat(outcome.type()).isEqualTo("jakarta.persistence.QueryTimeoutException");
        assertThat(outcome.millis()).isLessThan(4_000);
    }

    private static Outcome measure(Supplier<?> work) {
        long started = System.nanoTime();
        Throwable thrown = catchThrowable(work::get);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String type = thrown == null ? "none" : thrown.getClass().getName();
        boolean cancelled = false;
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            cancelled |= String.valueOf(t.getMessage()).contains("ORA-01013");
        }
        return new Outcome(millis, cancelled, type);
    }

    private record Outcome(long millis, boolean cancelled, String type) {
    }
}
