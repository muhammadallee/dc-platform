package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The platform's task decorator: on by default, one decorator only, backs off to the service's or Boot's. */
class CoreContextPropagationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(TaskExecutionAutoConfiguration.class, CoreContextPropagationAutoConfiguration.class));

    @Test
    void decoratesBootsExecutorByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskDecorator.class).hasBean("platformContextPropagatingTaskDecorator");
            ThreadPoolTaskExecutor executor = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor).extracting("taskDecorator").isSameAs(context.getBean(TaskDecorator.class));
        });
    }

    @Test
    void backsOffToTheServicesDecorator() {
        TaskDecorator mine = runnable -> runnable;
        runner.withBean("myDecorator", TaskDecorator.class, () -> mine)
                .run(context -> assertThat(context).getBean(TaskDecorator.class).isSameAs(mine));
    }

    @Test
    void backsOffToBootsDecoratorWhenTheServiceTurnsItOn() {
        runner.withPropertyValues("spring.task.execution.propagate-context=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskDecorator.class).hasBean("contextPropagatingTaskDecorator");
                    assertThat(context).doesNotHaveBean("platformContextPropagatingTaskDecorator");
                });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.core.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ContextPropagatingTaskDecorator.class));
    }

    @Test
    void inactiveWithoutContextPropagation() {
        runner.withClassLoader(new FilteredClassLoader(ContextSnapshotFactory.class))
                .run(context -> assertThat(context).doesNotHaveBean("platformContextPropagatingTaskDecorator"));
    }
}
