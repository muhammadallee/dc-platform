package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

// Dedicated startup-failure type (phase-16 A.2) so MissingFlywayFailureAnalyzer can target exactly
// the "JPA on, migrations required, Flyway absent" case. Extends IllegalStateException — it still IS
// an illegal startup state — so callers/tests catching that continue to work.
public final class MissingFlywayException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public MissingFlywayException(String message) {
        super(message);
    }
}
