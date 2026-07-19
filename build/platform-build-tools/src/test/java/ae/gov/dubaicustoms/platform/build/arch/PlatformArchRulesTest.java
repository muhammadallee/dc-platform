package ae.gov.dubaicustoms.platform.build.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** Proves every constitution rule FIRES on a violating fixture and passes on a clean one. */
class PlatformArchRulesTest {

    private static JavaClasses importOf(String... packages) {
        return new ClassFileImporter().importPackages(packages);
    }

    @Test
    void allExposesTheFullConstitution() {
        assertThat(PlatformArchRules.all()).hasSize(6);
    }

    @Test
    void internalIsolationAllowsOwnInternalsAndRejectsForeignOnes() {
        assertThatCode(() -> PlatformArchRules.internalPackagesAreModulePrivate()
                .check(importOf("ae.gov.dubaicustoms.platform.alpha")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> PlatformArchRules.internalPackagesAreModulePrivate()
                .check(importOf("ae.gov.dubaicustoms.platform.alpha", "ae.gov.dubaicustoms.platform.beta")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BetaLeak");
    }

    @Test
    void apiPackagesMayOnlyUseJdkSpringAnnotationsAndCore() {
        assertThatCode(() -> PlatformArchRules.apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore()
                .check(importOf("ae.gov.dubaicustoms.platform.gamma").that(
                        com.tngtech.archunit.base.DescribedPredicate.describe("clean fixture only",
                                c -> c.getSimpleName().equals("GammaContract")))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> PlatformArchRules.apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore()
                .check(importOf("ae.gov.dubaicustoms.platform.gamma")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GammaDirty");
    }

    @Test
    void fieldInjectionIsRejected() {
        assertThatThrownBy(() -> PlatformArchRules.noFieldInjection()
                .check(importOf("ae.gov.dubaicustoms.platform.delta")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DeltaFieldInjected");
    }

    @Test
    void capabilityCyclesAreRejected() {
        assertThatThrownBy(() -> PlatformArchRules.noCapabilityCycles()
                .check(importOf("ae.gov.dubaicustoms.platform.cyca", "ae.gov.dubaicustoms.platform.cycb")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle");
    }

    @Test
    void autoConfigurationsMustBeAnnotatedAndLiveInAutoconfigurePackage() {
        assertThatCode(() -> PlatformArchRules.autoConfigurationsAreAnnotatedAndPlaced()
                .check(importOf("ae.gov.dubaicustoms.platform.zeta")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> PlatformArchRules.autoConfigurationsAreAnnotatedAndPlaced()
                .check(importOf("ae.gov.dubaicustoms.platform.epsilon")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MisplacedAutoConfiguration");
    }

    @Test
    void configurationPropertiesMustBeRecords() {
        assertThatCode(() -> PlatformArchRules.configurationPropertiesAreRecords()
                .check(importOf("ae.gov.dubaicustoms.platform.theta")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> PlatformArchRules.configurationPropertiesAreRecords()
                .check(importOf("ae.gov.dubaicustoms.platform.eta")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("EtaSettings");
    }
}
