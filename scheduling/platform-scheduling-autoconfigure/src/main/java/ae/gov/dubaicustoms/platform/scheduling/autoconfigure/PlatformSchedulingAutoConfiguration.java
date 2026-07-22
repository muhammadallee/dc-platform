package ae.gov.dubaicustoms.platform.scheduling.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.locking.LockManager;
import ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal.LockedScheduleAdvisorFactory;
import ae.gov.dubaicustoms.platform.scheduling.autoconfigure.internal.LockedScheduleAutoProxyRegistrar;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/*
 * Activates when: dc.platform.scheduling.enabled != false.
 * Backs off when: the user defines their own TaskScheduler (the platform virtual-thread scheduler).
 * Beans: taskScheduler — a SimpleAsyncTaskScheduler running each firing on a virtual thread, so a
 *                 blocking scheduled job never starves a small platform thread pool;
 *        schedulingCapabilityDescriptor — one line in the startup capability banner;
 *        lockedScheduleAdvisor — (only when the locking capability's LockManager is on the classpath)
 *                 a plain AOP advisor running @LockedSchedule methods under LockManager.withLock, so a
 *                 scheduled job fires on a single instance cluster-wide; runs unlocked (warn once) when
 *                 no LockManager bean exists.
 * @EnableScheduling turns on Spring's @Scheduled processing; the advisor uses the plain (non-AspectJ)
 *                 auto-proxy creator (LockedScheduleAutoProxyRegistrar) so no aspectjweaver is needed.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "dc.platform.scheduling", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableScheduling
@EnableConfigurationProperties(SchedulingProperties.class)
public class PlatformSchedulingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TaskScheduler taskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("platform-scheduler-");
        return scheduler;
    }

    @Bean
    CapabilityDescriptor schedulingCapabilityDescriptor() {
        return new CapabilityDescriptor("scheduling", "ACTIVE", "");
    }

    /*
     * Contributed only when the locking capability's LockManager type is present (guarded reference to
     * another capability's api, CLAUDE.md rule 5). The advisor still resolves the LockManager through
     * an ObjectProvider, so a locking-on-classpath-but-not-configured app runs @LockedSchedule methods
     * unlocked with a one-time warning rather than failing.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(LockManager.class)
    @Import(LockedScheduleAutoProxyRegistrar.class)
    static class LockedScheduleConfiguration {

        // ROLE_INFRASTRUCTURE: the InfrastructureAdvisorAutoProxyCreator only considers Advisor beans
        // with this role — a plain application-role bean would be silently ignored (decision D26).
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnMissingBean(name = "lockedScheduleAdvisor")
        Advisor lockedScheduleAdvisor(ObjectProvider<LockManager> lockManager) {
            return LockedScheduleAdvisorFactory.create(lockManager);
        }
    }
}
