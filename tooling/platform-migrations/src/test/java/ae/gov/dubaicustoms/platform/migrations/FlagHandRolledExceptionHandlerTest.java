package ae.gov.dubaicustoms.platform.migrations;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

class FlagHandRolledExceptionHandlerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The fixtures reference Spring's @RestControllerAdvice without spring-web on the parser
        // classpath; the recipe matches by simple name, so skip type attribution validation.
        spec.recipe(new FlagHandRolledExceptionHandler())
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void flagsRestControllerAdviceForReview() {
        rewriteRun(java(
                """
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                class MyExceptionHandler {
                }
                """,
                """
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                /*~~(TODO(DC Platform): platform maps exceptions to RFC 9457 already; prefer PlatformException subtypes and remove this advice (docs/modules/errors.md).)~~>*/@RestControllerAdvice
                class MyExceptionHandler {
                }
                """));
    }

    @Test
    void leavesOrdinaryClassesUntouched() {
        rewriteRun(java(
                """
                class PlainService {
                }
                """));
    }
}
