package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

// Dedicated startup-failure type so OutOfOrderMigrationsFailureAnalyzer can target exactly the
// "out-of-order migrations outside local" case (D112). An illegal startup state, like MissingFlywayException.
public final class OutOfOrderMigrationsException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public OutOfOrderMigrationsException(String message) {
        super(message);
    }
}
