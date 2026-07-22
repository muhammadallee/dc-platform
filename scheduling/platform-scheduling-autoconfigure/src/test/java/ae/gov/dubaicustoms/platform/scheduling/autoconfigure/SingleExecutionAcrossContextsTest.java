package ae.gov.dubaicustoms.platform.scheduling.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.locking.autoconfigure.JdbcLockProviderAutoConfiguration;
import ae.gov.dubaicustoms.platform.locking.autoconfigure.PlatformLockingAutoConfiguration;
import ae.gov.dubaicustoms.platform.locking.autoconfigure.RedisLockProviderAutoConfiguration;
import ae.gov.dubaicustoms.platform.scheduling.LockedSchedule;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Proves {@code @LockedSchedule} yields single execution across the cluster: two independent Spring
 * contexts share one H2 lock table (as two nodes would share a database). While one context holds the
 * lock inside the method, the other's firing is skipped. No Docker.
 */
class SingleExecutionAcrossContextsTest {

    // A shared in-memory H2 kept alive for the JVM (DB_CLOSE_DELAY=-1) so both contexts see one table.
    private static final String SHARED_URL = "jdbc:h2:mem:sched-lock;DB_CLOSE_DELAY=-1";

    @BeforeAll
    static void createLockTable() throws Exception {
        String ddl = StreamUtils.copyToString(
                new ClassPathResource("db/migration-platform-locking/V1__create_platform_lock.sql").getInputStream(),
                StandardCharsets.UTF_8);
        new JdbcTemplate(dataSource()).execute(ddl);
    }

    @BeforeEach
    void resetCounters() {
        Job.executions.set(0);
        Job.entered = null;
        Job.release = null;
    }

    @Test
    void secondNodeSkipsWhileFirstHoldsTheLock() throws Exception {
        try (AnnotationConfigApplicationContext nodeA = new AnnotationConfigApplicationContext(ContextConfig.class);
                AnnotationConfigApplicationContext nodeB = new AnnotationConfigApplicationContext(ContextConfig.class)) {
            Job jobA = nodeA.getBean(Job.class);
            Job jobB = nodeB.getBean(Job.class);

            Job.entered = new CountDownLatch(1);
            Job.release = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> nodeARun = executor.submit(() -> {
                    jobA.run();
                    return null;
                });

                assertThat(Job.entered.await(5, TimeUnit.SECONDS)).isTrue(); // node A holds the lock
                jobB.run(); // lock held by A -> skipped
                assertThat(Job.executions.get()).isEqualTo(1);

                Job.release.countDown();
                nodeARun.get(5, TimeUnit.SECONDS);
                assertThat(Job.executions.get()).isEqualTo(1);

                // With the lock released, a later firing runs again.
                Job.entered = null;
                Job.release = null;
                jobB.run();
                assertThat(Job.executions.get()).isEqualTo(2);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(SHARED_URL, "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            RedisLockProviderAutoConfiguration.class,
            JdbcLockProviderAutoConfiguration.class,
            PlatformLockingAutoConfiguration.class,
            PlatformSchedulingAutoConfiguration.class})
    static class ContextConfig {

        @Bean
        DataSource dataSource() {
            return SingleExecutionAcrossContextsTest.dataSource();
        }

        @Bean
        Job job() {
            return new Job();
        }
    }

    /** A locked scheduled job; the first invocation blocks inside the lock until released. */
    static class Job {
        static final AtomicInteger executions = new AtomicInteger();
        static volatile CountDownLatch entered;
        static volatile CountDownLatch release;

        @LockedSchedule(name = "nightly", atMost = "PT1M")
        public void run() {
            executions.incrementAndGet();
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            if (enteredLatch != null && releaseLatch != null) {
                enteredLatch.countDown();
                try {
                    releaseLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
