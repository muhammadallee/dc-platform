package ae.gov.dubaicustoms.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the annotation contract: retention/target and the default (empty) resource expression. */
class AuditedAnnotationTest {

    @Audited(action = "order.create", resourceExpression = "#result.id")
    void annotated() {
    }

    @Audited(action = "order.list")
    void annotatedWithDefaults() {
    }

    @Test
    void carriesActionAndResourceExpression() throws Exception {
        Audited annotation = AuditedAnnotationTest.class
                .getDeclaredMethod("annotated").getAnnotation(Audited.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.action()).isEqualTo("order.create");
        assertThat(annotation.resourceExpression()).isEqualTo("#result.id");
    }

    @Test
    void resourceExpressionDefaultsToEmpty() throws Exception {
        Audited annotation = AuditedAnnotationTest.class
                .getDeclaredMethod("annotatedWithDefaults").getAnnotation(Audited.class);

        assertThat(annotation.resourceExpression()).isEmpty();
    }
}
