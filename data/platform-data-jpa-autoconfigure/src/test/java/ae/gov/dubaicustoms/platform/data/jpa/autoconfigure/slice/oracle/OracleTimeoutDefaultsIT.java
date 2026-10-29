package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
 * The platform's statement bounds where no lever is set by the caller (scope R2, D110), with the two
 * defaults shortened to keep the run short — transaction 1 s, query 4 s instead of 30 s each:
 * <ul>
 *   <li>a query outside any transaction — a declared repository query has none of its own, so does a
 *       non-transactional service — is cancelled by the default query timeout, and the connection is
 *       reused cleanly afterwards;</li>
 *   <li>inside a transaction, the remaining transaction time wins over that default;</li>
 *   <li>a transaction whose deadline has passed fails its next statement: a query with Spring's
 *       {@code TransactionTimedOutException}, a flush or commit with Hibernate's
 *       {@code TransactionException("Transaction timeout expired")} — the chain the errors capability
 *       classifies as 503 (D111).</li>
 * </ul>
 * The work is the same plain-SQL cross join as {@link OracleTimeoutLeversIT} (about 10 s uncancelled).
 * Excluded from the default build (docker JUnit tag); run under {@code -Pdocker}.
 */
@Tag("docker")
@DataJpaTest(properties = {
        "spring.flyway.locations=classpath:db/migration-platform-data-oracle",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.transaction.default-timeout=1s",
        "spring.jpa.properties.jakarta.persistence.query.timeout=4000"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OracleTimeoutDefaultsIT {

    private static final String HEAVY_SQL =
            "select count(*) from (select level l from dual connect by level <= 20000) a, (select level l from dual connect by level <= 20000) b";

    private static final String EXPIRED = "Transaction timeout expired";

    @DynamicPropertySource
    static void oracle(DynamicPropertyRegistry registry) {
        OracleContainers.register(registry);
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private OracleWidgetRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aDeclaredQueryOutsideATransactionIsCancelledByTheDefaultQueryTimeout() {
        Outcome outcome = measure(() -> repository.heavyCount());
        assertThat(outcome.chain()).as(outcome.toString()).startsWith("org.springframework.dao.QueryTimeoutException");
        assertThat(outcome.millis()).isBetween(3_500L, 7_000L);
        assertThat(repository.count()).as("the cancelled connection is reused cleanly").isGreaterThanOrEqualTo(0);
    }

    @Test
    void anEntityManagerQueryOutsideATransactionIsCancelledByTheDefaultQueryTimeout() {
        Outcome outcome = measure(() -> em.createNativeQuery(HEAVY_SQL).getSingleResult());
        assertThat(outcome.chain()).as(outcome.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
        assertThat(outcome.millis()).isBetween(3_500L, 7_000L);
        assertThat(((Number) em.createNativeQuery("select 1 from dual").getSingleResult()).intValue()).isEqualTo(1);
    }

    @Test
    void insideATransactionTheRemainingTransactionTimeWins() {
        Outcome direct = measure(() -> tx().execute(status -> em.createNativeQuery(HEAVY_SQL).getSingleResult()));
        assertThat(direct.chain()).as(direct.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
        assertThat(direct.millis()).isLessThan(3_500L);
        Outcome declared = measure(() -> tx().execute(status -> repository.heavyCount()));
        assertThat(declared.chain()).as(declared.toString()).startsWith("org.springframework.dao.QueryTimeoutException");
        assertThat(declared.millis()).isLessThan(3_500L);
    }

    @Test
    void aLongerTransactionTimeoutIsNotCutShortByTheQueryDefault() {
        // A batch transaction given more time than the query default keeps it (CHANGELOG advice).
        TransactionTemplate longer = tx();
        longer.setTimeout(6);
        Outcome outcome = measure(() -> longer.execute(status -> em.createNativeQuery(HEAVY_SQL).getSingleResult()));
        assertThat(outcome.chain()).as(outcome.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
        assertThat(outcome.millis()).isGreaterThanOrEqualTo(5_500L);
    }

    @Test
    void aQueryAfterTheDeadlineFailsWithSpringsTimedOutException() {
        Outcome outcome = measure(() -> tx().execute(status -> {
            repository.count();
            pastTheDeadline();
            return repository.count();
        }));
        assertThat(outcome.chain()).as(outcome.toString())
                .containsExactly("org.springframework.transaction.TransactionTimedOutException");
    }

    @Test
    void aFlushAfterTheDeadlineFailsWithHibernatesExpiry() {
        Outcome outcome = measure(() -> tx().execute(status -> {
            em.persist(widget("EXP-FLUSH-" + System.nanoTime()));
            pastTheDeadline();
            em.flush();
            return null;
        }));
        assertThat(outcome.chain()).as(outcome.toString()).containsExactly("org.hibernate.TransactionException");
        assertThat(outcome.message()).isEqualTo(EXPIRED);
    }

    @Test
    void aCommitAfterTheDeadlineFailsWithHibernatesExpiryWrappedBySpring() {
        Outcome outcome = measure(() -> tx().execute(status -> {
            repository.save(widget("EXP-COMMIT-" + System.nanoTime()));
            pastTheDeadline();
            return null;
        }));
        assertThat(outcome.chain()).as(outcome.toString())
                .containsExactly("org.springframework.orm.jpa.JpaSystemException", "org.hibernate.TransactionException");
        assertThat(outcome.message()).isEqualTo(EXPIRED);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static OracleWidget widget(String reference) {
        OracleWidget widget = new OracleWidget();
        widget.referenceCode = reference;
        return widget;
    }

    private static void pastTheDeadline() {
        try {
            Thread.sleep(1_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Outcome measure(Supplier<?> work) {
        long started = System.nanoTime();
        Throwable thrown = catchThrowable(work::get);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        List<String> chain = new ArrayList<>();
        String innermost = null;
        for (Throwable t = thrown; t != null && chain.size() < 20; t = t.getCause()) {
            chain.add(t.getClass().getName());
            innermost = t.getMessage();
        }
        System.out.println("observed: " + millis + " ms, chain " + chain);
        return new Outcome(millis, chain, innermost);
    }

    /** {@code message} is the innermost exception's message. */
    private record Outcome(long millis, List<String> chain, String message) {
    }
}
