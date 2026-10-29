package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.Session;
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
 *   <li>inside a transaction, the remaining transaction time wins over that default — for a query
 *       created through Spring's {@code EntityManager} proxy or the unwrapped {@code Session}'s
 *       {@code createQuery}/{@code createNativeQuery}, but not for Hibernate's {@code SelectionQuery} or a
 *       query on the raw delegate (residual R2-F2, pinned);</li>
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

    /** The same work in JPQL: a cross join of generated rows ({@link OracleGeneratedRow}). */
    private static final String HEAVY_JPQL = "select count(*) from OracleGeneratedRow a, OracleGeneratedRow b";

    private static final String EXPIRED ="Transaction timeout expired";

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
    void aQueryCreatedOnTheNativeSessionKeepsTheTransactionTime() {
        // Review round 2, R2-F2: a query created on the unwrapped Hibernate Session, not through
        // Spring's EntityManager proxy, inside transactions shorter and longer than the query default.
        Outcome shorter = measure(() -> tx().execute(status ->
                em.unwrap(Session.class).createNativeQuery(HEAVY_SQL).getSingleResult()));
        assertThat(shorter.chain()).as(shorter.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
        assertThat(shorter.millis()).as("1 s transaction, 4 s query default").isLessThan(3_500L);
        TransactionTemplate longer = tx();
        longer.setTimeout(6);
        Outcome kept = measure(() -> longer.execute(status ->
                em.unwrap(Session.class).createNativeQuery(HEAVY_SQL).getSingleResult()));
        assertThat(kept.chain()).as(kept.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
        Outcome jpql = measure(() -> longer.execute(status ->
                em.unwrap(Session.class).createQuery(HEAVY_JPQL, Long.class).getSingleResult()));
        assertThat(jpql.millis()).as("6 s transaction, Session.createQuery: " + jpql).isGreaterThanOrEqualTo(5_500L);
    }

    @Test
    void aQueryThatBypassesSpringsQueryHandlingTakesTheQueryDefaultInsteadOfTheTransactionTime() {
        // Residual (review round 2, R2-F2), pinned so a change is noticed: Spring hands the remaining
        // transaction time only to a jakarta.persistence.Query it creates. Hibernate's SelectionQuery and a
        // query on the raw delegate get the query default — past a shorter transaction's deadline, or
        // cutting a longer one short. Still bounded: by the default here, by the read timeout behind it.
        Outcome past = measure(() -> tx().execute(status ->
                em.unwrap(Session.class).createSelectionQuery(HEAVY_JPQL, Long.class).getSingleResult()));
        assertThat(past.chain()).as(past.toString()).startsWith("jakarta.persistence.QueryTimeoutException");
        assertThat(past.millis()).as("1 s transaction, selection query").isBetween(3_500L, 7_000L);
        TransactionTemplate longer = tx();
        longer.setTimeout(6);
        Outcome selection = measure(() -> longer.execute(status ->
                em.unwrap(Session.class).createSelectionQuery(HEAVY_JPQL, Long.class).getSingleResult()));
        assertThat(selection.millis()).as("6 s transaction, selection query: " + selection).isLessThan(5_500L);
        Outcome delegate = measure(() -> longer.execute(status ->
                ((EntityManager) em.getDelegate()).createQuery(HEAVY_JPQL, Long.class).getSingleResult()));
        assertThat(delegate.millis()).as("6 s transaction, raw delegate: " + delegate).isLessThan(5_500L);
        // The documented workaround: a timeout set on the query itself.
        Outcome own = measure(() -> longer.execute(status -> em.unwrap(Session.class)
                .createSelectionQuery(HEAVY_JPQL, Long.class).setTimeout(6).getSingleResult()));
        assertThat(own.millis()).as("own timeout: " + own).isGreaterThanOrEqualTo(5_500L);
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
