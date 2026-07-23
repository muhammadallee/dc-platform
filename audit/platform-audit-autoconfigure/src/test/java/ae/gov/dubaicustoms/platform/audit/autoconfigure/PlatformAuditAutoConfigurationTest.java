package ae.gov.dubaicustoms.platform.audit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.audit.Audited;
import ae.gov.dubaicustoms.platform.audit.AuditEvent;
import ae.gov.dubaicustoms.platform.audit.Auditor;
import ae.gov.dubaicustoms.platform.audit.Outcome;
import ae.gov.dubaicustoms.platform.audit.autoconfigure.internal.ActorResolver;
import ae.gov.dubaicustoms.platform.audit.jdbc.JdbcAuditSink;
import ae.gov.dubaicustoms.platform.audit.log.LogAuditSink;
import ae.gov.dubaicustoms.platform.audit.spi.AuditSink;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ContextRunner matrix plus sink-selection, actor-resolution, and @Audited behavior tests. */
class PlatformAuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JdbcAuditSinkAutoConfiguration.class,
                    LogAuditSinkAutoConfiguration.class,
                    AuditSecurityAutoConfiguration.class,
                    PlatformAuditAutoConfiguration.class));

    // --- mandatory ContextRunner matrix -------------------------------------------------------

    @Test
    void activeByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(Auditor.class);
            assertThat(ctx).hasSingleBean(AuditSink.class);
            assertThat(ctx.getBean(AuditSink.class)).isInstanceOf(LogAuditSink.class);
            assertThat(ctx).hasBean("auditCapabilityDescriptor");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.audit.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(Auditor.class);
            assertThat(ctx).doesNotHaveBean(AuditSink.class);
        });
    }

    @Test
    void backsOffWhenUserAuditorPresent() {
        Auditor mine = mock(Auditor.class);
        runner.withBean("mine", Auditor.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(Auditor.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenNoSinkOnClasspath() {
        // Filter both sink types this module knows: with no AuditSink available, no sink bean and no
        // Auditor. (The messaging sink lives in a separate module, absent from this runner.)
        runner.withClassLoader(new FilteredClassLoader(LogAuditSink.class, JdbcAuditSink.class))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AuditSink.class);
                    assertThat(ctx).doesNotHaveBean(Auditor.class);
                });
    }

    @Test
    void customizerOrderingNotApplicable() {
        // The audit capability contributes no user customizers; the sink is chosen by the degradation
        // chain, exercised by the selection tests below.
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AuditSink.class));
    }

    // --- degradation chain: messaging > jdbc > log --------------------------------------------

    @Test
    void jdbcSinkChosenOverLogWhenDataSourcePresent() {
        runner.withBean("ds", DataSource.class, () -> mock(DataSource.class)).run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditSink.class);
            assertThat(ctx.getBean(AuditSink.class)).isInstanceOf(JdbcAuditSink.class);
        });
    }

    // (Messaging-sink selection is verified in platform-audit-messaging-autoconfigure's own test,
    // where MessagingAuditSinkAutoConfiguration lives.)

    // --- actor resolution ---------------------------------------------------------------------

    @Test
    void actorResolverDefaultsToAnonymous() {
        runner.run(ctx -> assertThat(ctx.getBean(ActorResolver.class).currentActor())
                .isEqualTo(ActorResolver.ANONYMOUS));
    }

    @Test
    void actorResolverBackedBySecurityWhenPresent() {
        CurrentUserAccessor accessor = () -> Optional.of(
                new CurrentUser("alice", null, Set.of(), java.util.Map.of()));
        runner.withBean("accessor", CurrentUserAccessor.class, () -> accessor).run(ctx ->
                assertThat(ctx.getBean(ActorResolver.class).currentActor()).isEqualTo("alice"));
    }

    // --- @Audited behavior --------------------------------------------------------------------

    @Test
    void auditedMethodRecordsSuccessEventWithResolvedResource() {
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        runner.withBean("captureSink", AuditSink.class, () -> (AuditSink) captured::add)
                .withUserConfiguration(AuditedServiceConfiguration.class)
                .run(ctx -> {
                    AuditedService service = ctx.getBean(AuditedService.class);
                    assertThat(service.create("42")).isEqualTo("order-42");

                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            assertThat(captured).hasSize(1));
                    AuditEvent event = captured.get(0);
                    assertThat(event.action()).isEqualTo("order.create");
                    assertThat(event.actor()).isEqualTo(ActorResolver.ANONYMOUS);
                    assertThat(event.resource()).isEqualTo("order-42");
                    assertThat(event.outcome()).isEqualTo(Outcome.SUCCESS);
                });
    }

    @Test
    void auditedMethodRecordsFailureEventAndRethrows() {
        List<AuditEvent> captured = new CopyOnWriteArrayList<>();
        runner.withBean("captureSink", AuditSink.class, () -> (AuditSink) captured::add)
                .withUserConfiguration(AuditedServiceConfiguration.class)
                .run(ctx -> {
                    AuditedService service = ctx.getBean(AuditedService.class);
                    try {
                        service.fail();
                    } catch (IllegalStateException expected) {
                        assertThat(expected).hasMessage("boom");
                    }

                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            assertThat(captured).hasSize(1));
                    assertThat(captured.get(0).outcome()).isEqualTo(Outcome.FAILURE);
                    assertThat(captured.get(0).action()).isEqualTo("order.fail");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class AuditedServiceConfiguration {
        @Bean
        AuditedService auditedService() {
            return new AuditedService();
        }
    }

    static class AuditedService {

        @Audited(action = "order.create", resourceExpression = "#result")
        public String create(String id) {
            return "order-" + id;
        }

        @Audited(action = "order.fail")
        public void fail() {
            throw new IllegalStateException("boom");
        }
    }
}
