package ae.gov.dubaicustoms.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the annotation contract: retention/target and the default TTL. */
class IdempotentAnnotationTest {

    @Idempotent(keyExpression = "#id")
    void annotated() {
    }

    @Test
    void defaultTtlIsTwentyFourHours() throws Exception {
        Idempotent annotation = IdempotentAnnotationTest.class
                .getDeclaredMethod("annotated").getAnnotation(Idempotent.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.keyExpression()).isEqualTo("#id");
        assertThat(annotation.ttl()).isEqualTo("24h");
    }
}
