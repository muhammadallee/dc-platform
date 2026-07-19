package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The mandatory 5-case ContextRunner matrix for PlatformBannerAutoConfiguration. */
@ExtendWith(OutputCaptureExtension.class)
class PlatformBannerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformBannerAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(context -> {
            assertThat(context).hasBean("platformBannerRunner");
            assertThat(context).hasSingleBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.core.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void bannerSwitchDisablesOnlyTheRunner() {
        runner.withPropertyValues("dc.platform.core.banner-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                    // The descriptor stays: other report consumers may still want it.
                    assertThat(context).hasSingleBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void backsOffWhenUserRunnerPresent() {
        ApplicationRunner mine = args -> { };
        runner.withBean("platformBannerRunner", ApplicationRunner.class, () -> mine)
                .run(context -> assertThat(context).getBean("platformBannerRunner").isSameAs(mine));
    }

    @Test
    void inactiveWhenCoreApiMissing() {
        runner.withClassLoader(new FilteredClassLoader(CorrelationId.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlatformBannerAutoConfiguration.class));
    }

    @Test
    void bannerLogsOneSortedLine(CapturedOutput output) {
        runner.withBean("zetaCapabilityDescriptor", CapabilityDescriptor.class,
                        () -> new CapabilityDescriptor("zeta", "ACTIVE", "inmemory"))
                .withBean("alphaCapabilityDescriptor", CapabilityDescriptor.class,
                        () -> new CapabilityDescriptor("alpha", "ACTIVE", ""))
                .run(context -> {
                    context.getBean("platformBannerRunner", ApplicationRunner.class)
                            .run(new DefaultApplicationArguments());
                    assertThat(output.getOut())
                            .contains("platform: alpha[ACTIVE], core[ACTIVE], zeta[ACTIVE] (inmemory)");
                });
    }
}
