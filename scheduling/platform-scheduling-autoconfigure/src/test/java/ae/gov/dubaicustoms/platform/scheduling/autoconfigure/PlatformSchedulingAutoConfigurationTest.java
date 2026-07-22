package ae.gov.dubaicustoms.platform.scheduling.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.locking.LockManager;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** The mandatory ContextRunner matrix for PlatformSchedulingAutoConfiguration. */
class PlatformSchedulingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformSchedulingAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskScheduler.class);
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TaskScheduler.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserTaskSchedulerPresent() {
        ThreadPoolTaskScheduler mine = new ThreadPoolTaskScheduler();
        runner.withBean("taskScheduler", TaskScheduler.class, () -> mine)
                .run(context -> assertThat(context.getBean(TaskScheduler.class)).isSameAs(mine));
    }

    @Test
    void lockedScheduleAdvisorPresentWhenLockManagerOnClasspath() {
        runner.run(context -> {
            assertThat(context).hasBean("lockedScheduleAdvisor");
            assertThat(context).getBean("lockedScheduleAdvisor").isInstanceOf(Advisor.class);
        });
    }

    @Test
    void lockedScheduleAdvisorInactiveWhenLockingClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(LockManager.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("lockedScheduleAdvisor");
                    // Scheduling itself is unaffected.
                    assertThat(context).hasSingleBean(TaskScheduler.class);
                });
    }
}
