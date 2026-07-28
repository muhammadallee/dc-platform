package ae.gov.dubaicustoms.platform.migrations;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

/**
 * Flags a hand-rolled {@code @ControllerAdvice}/{@code @RestControllerAdvice} class for review rather
 * than deleting it — the platform already maps every exception to RFC 9457, so the advice is usually
 * redundant, but removing it automatically could drop custom handling. The marker comment states the
 * rationale and the platform alternative; a human decides (phase-16 D.2).
 */
public class FlagHandRolledExceptionHandler extends Recipe {

    private static final String TODO =
            "TODO(DC Platform): platform maps exceptions to RFC 9457 already; prefer PlatformException "
                    + "subtypes and remove this advice (docs/modules/errors.md).";

    @Override
    public String getDisplayName() {
        return "Flag hand-rolled @ControllerAdvice for review";
    }

    @Override
    public String getDescription() {
        return "Adds a review comment to any @ControllerAdvice or @RestControllerAdvice class, since the "
                + "DC Platform already provides RFC-9457 exception mapping. Does not delete the advice.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getMarkers().findFirst(SearchResult.class).isPresent()) {
                    return cd; // already flagged
                }
                boolean handRolledAdvice = cd.getLeadingAnnotations().stream()
                        .map(J.Annotation::getSimpleName)
                        .anyMatch(name -> "ControllerAdvice".equals(name) || "RestControllerAdvice".equals(name));
                return handRolledAdvice ? SearchResult.found(cd, TODO) : cd;
            }
        };
    }
}
