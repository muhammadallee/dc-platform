package ae.gov.dubaicustoms.platform.flags.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.flags.FeatureFlags;
import ae.gov.dubaicustoms.platform.flags.FeatureGate;
import ae.gov.dubaicustoms.platform.flags.inmemory.InMemoryFlagProvider;
import ae.gov.dubaicustoms.platform.flags.spi.FlagProvider;
import dev.openfeature.sdk.Client;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ContextRunner matrix, provider selection, static-flag coercion, and the @FeatureGate behavior table. */
class PlatformFlagsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenFeatureFlagProviderAutoConfiguration.class,
                    InMemoryFlagProviderAutoConfiguration.class,
                    FlagsSecurityAutoConfiguration.class,
                    PlatformFlagsAutoConfiguration.class));

    @Test
    void activeByDefaultUsesTheInMemoryProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FeatureFlags.class);
            assertThat(context).getBean(FlagProvider.class).isInstanceOf(InMemoryFlagProvider.class);
            assertThat(context).getBean(CapabilityDescriptor.class)
                    .extracting(CapabilityDescriptor::detail).isEqualTo("inmemory");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.flags.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(FeatureFlags.class);
            assertThat(context).doesNotHaveBean(FlagProvider.class);
            assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
        });
    }

    @Test
    void backsOffWhenUserFeatureFlagsPresent() {
        FeatureFlags mine = new FeatureFlags() {
            @Override
            public boolean enabled(String flag) {
                return true;
            }

            @Override
            public <T> T value(String flag, T defaultValue) {
                return defaultValue;
            }
        };
        runner.withBean("mine", FeatureFlags.class, () -> mine)
                .run(context -> assertThat(context.getBean(FeatureFlags.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenNoProviderOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(InMemoryFlagProvider.class, Client.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FeatureFlags.class);
                    assertThat(context).doesNotHaveBean(CapabilityDescriptor.class);
                });
    }

    @Test
    void openFeatureWinsWhenAClientIsPresent() {
        runner.withBean(Client.class, () -> mock(Client.class)).run(context -> {
            assertThat(context).getBean(FlagProvider.class).isNotInstanceOf(InMemoryFlagProvider.class);
            assertThat(context).getBean(CapabilityDescriptor.class)
                    .extracting(CapabilityDescriptor::detail).isEqualTo("openfeature");
        });
    }

    @Test
    void staticFlagsAreEvaluatedAndCoercedToTheCallersType() {
        runner.withPropertyValues(
                        "dc.platform.flags.static.beta=true",
                        "dc.platform.flags.static.batch-size=250")
                .run(context -> {
                    FeatureFlags flags = context.getBean(FeatureFlags.class);
                    assertThat(flags.enabled("beta")).isTrue();
                    assertThat(flags.enabled("unknown")).isFalse();
                    assertThat(flags.value("batch-size", 100)).isEqualTo(250);
                    assertThat(flags.value("missing", 100)).isEqualTo(100);
                    assertThat(flags.value("batch-size", "def")).isEqualTo("250");
                });
    }

    @Test
    void featureGateRunsWhenOnAndReturnsNeutralValuesWhenOff() {
        runner.withPropertyValues("dc.platform.flags.static.on=true", "dc.platform.flags.static.off=false")
                .withUserConfiguration(GatedConfig.class)
                .run(context -> {
                    GatedService service = context.getBean(GatedService.class);

                    assertThat(service.enabledString()).isEqualTo("ran");

                    assertThat(service.gatedBoolean()).isFalse();
                    assertThat(service.gatedBoxedBoolean()).isFalse();
                    assertThat(service.gatedOptional()).isEmpty();
                    assertThat(service.gatedString()).isNull();
                    assertThat(service.gatedInt()).isZero();

                    service.ranVoid().set(true);
                    service.gatedVoid();
                    // Gated-off void method body must not run, so the flag stays true (was not reset).
                    assertThat(service.ranVoid()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class GatedConfig {
        @Bean
        GatedService gatedService() {
            return new GatedService();
        }
    }

    /** A bean whose methods are gated behind feature flags. */
    static class GatedService {
        private final AtomicBoolean ranVoid = new AtomicBoolean(false);

        @FeatureGate("on")
        public String enabledString() {
            return "ran";
        }

        @FeatureGate("off")
        public boolean gatedBoolean() {
            return true;
        }

        @FeatureGate("off")
        public Boolean gatedBoxedBoolean() {
            return Boolean.TRUE;
        }

        @FeatureGate("off")
        public Optional<String> gatedOptional() {
            return Optional.of("value");
        }

        @FeatureGate("off")
        public String gatedString() {
            return "value";
        }

        @FeatureGate("off")
        public int gatedInt() {
            return 42;
        }

        @FeatureGate("off")
        public void gatedVoid() {
            ranVoid.set(false);
        }

        AtomicBoolean ranVoid() {
            return ranVoid;
        }
    }
}
