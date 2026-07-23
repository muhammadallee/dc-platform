package ae.gov.dubaicustoms.platform.flags.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Value semantics of FlagValue and EvaluationContext. */
class FlagSpiTypesTest {

    @Test
    void flagValueBooleanViewCoercesBooleansAndStrings() {
        assertThat(new FlagValue(Boolean.TRUE).asBoolean()).isTrue();
        assertThat(new FlagValue("true").asBoolean()).isTrue();
        assertThat(new FlagValue("TRUE").asBoolean()).isTrue();
        assertThat(new FlagValue("nope").asBoolean()).isFalse();
        assertThat(new FlagValue(42).asBoolean()).isFalse();
        assertThatThrownBy(() -> new FlagValue(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void evaluationContextCopiesAttributesDefensively() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("region", "AE");
        EvaluationContext ctx = new EvaluationContext(Optional.of("u1"), Optional.of("t1"), mutable);

        mutable.put("region", "tampered");
        assertThat(ctx.attributes()).containsEntry("region", "AE");
        assertThat(ctx.userId()).contains("u1");
        assertThat(ctx.tenantId()).contains("t1");
    }

    @Test
    void anonymousContextHasNoUserTenantOrAttributes() {
        EvaluationContext ctx = EvaluationContext.anonymous();
        assertThat(ctx.userId()).isEmpty();
        assertThat(ctx.tenantId()).isEmpty();
        assertThat(ctx.attributes()).isEmpty();
    }
}
