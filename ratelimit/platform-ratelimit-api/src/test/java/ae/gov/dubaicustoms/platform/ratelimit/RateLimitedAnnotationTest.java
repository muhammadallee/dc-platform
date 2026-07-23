package ae.gov.dubaicustoms.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the annotation contract: retention/target and the documented defaults. */
class RateLimitedAnnotationTest {

    @RateLimited(name = "search", permits = 20, window = "PT1S", keyExpression = "#user")
    void annotated() {
    }

    @RateLimited(name = "global")
    void annotatedWithDefaults() {
    }

    @Test
    void carriesAllAttributes() throws Exception {
        RateLimited annotation = RateLimitedAnnotationTest.class
                .getDeclaredMethod("annotated").getAnnotation(RateLimited.class);

        assertThat(annotation.name()).isEqualTo("search");
        assertThat(annotation.permits()).isEqualTo(20);
        assertThat(annotation.window()).isEqualTo("PT1S");
        assertThat(annotation.keyExpression()).isEqualTo("#user");
    }

    @Test
    void appliesDocumentedDefaults() throws Exception {
        RateLimited annotation = RateLimitedAnnotationTest.class
                .getDeclaredMethod("annotatedWithDefaults").getAnnotation(RateLimited.class);

        assertThat(annotation.permits()).isEqualTo(100);
        assertThat(annotation.window()).isEqualTo("PT1M");
        assertThat(annotation.keyExpression()).isEmpty();
    }
}
