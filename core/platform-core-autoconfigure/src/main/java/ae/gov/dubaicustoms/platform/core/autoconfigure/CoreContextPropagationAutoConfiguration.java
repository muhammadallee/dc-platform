package ae.gov.dubaicustoms.platform.core.autoconfigure;

import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/*
 * Activates when: Micrometer context-propagation on the classpath AND dc.platform.core.enabled != false
 * Backs off when: any TaskDecorator bean exists — the service's own, or Boot's contextPropagatingTaskDecorator when
 *                 the service sets spring.task.execution.propagate-context=true (Boot's executor builder applies a
 *                 decorator only when exactly one is defined, so the platform never adds a second)
 * Beans: platformContextPropagatingTaskDecorator — Spring's ContextPropagatingTaskDecorator over a snapshot factory
 *        that CLEARS missing values. Boot's own decorator does not, so a task captured with no trace, run on a thread
 *        that serves another caller (caller-runs, nested propagation), would see that caller's observation through
 *        Tracer (Phase 2 risk N1; HI-0016/HI-0023 for the MDC). With it, every registered ThreadLocalAccessor is
 *        reset for such a task. Boot's task-execution auto-configuration picks it up for applicationTaskExecutor
 *        (@Async, async MVC) and every executor built with its builders.
 * Order: after TaskExecutionAutoConfiguration, so a Boot decorator enabled by property is seen first.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration")
@ConditionalOnClass(ContextSnapshotFactory.class)
@ConditionalOnProperty(prefix = "dc.platform.core", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CoreContextPropagationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    ContextPropagatingTaskDecorator platformContextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator(ContextSnapshotFactory.builder().clearMissing(true).build());
    }
}
