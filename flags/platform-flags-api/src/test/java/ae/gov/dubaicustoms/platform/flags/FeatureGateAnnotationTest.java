package ae.gov.dubaicustoms.platform.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

/** The @FeatureGate contract: runtime-retained, method-targeted, carries the flag key. */
class FeatureGateAnnotationTest {

    @FeatureGate("demo-flag")
    void gated() {
    }

    @Test
    void isRuntimeRetainedAndCarriesTheFlagKey() throws NoSuchMethodException {
        FeatureGate gate = FeatureGateAnnotationTest.class
                .getDeclaredMethod("gated").getAnnotation(FeatureGate.class);
        assertThat(gate).isNotNull();
        assertThat(gate.value()).isEqualTo("demo-flag");
    }

    @Test
    void isMethodTargetedAndRuntimeRetained() {
        assertThat(FeatureGate.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(FeatureGate.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.METHOD);
    }
}
