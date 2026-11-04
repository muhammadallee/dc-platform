package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

/** Authorization is on with no way to find the current user, so {@code @RequiresPermission} could enforce nothing. */
public final class NoCurrentUserAccessorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the failure. */
    public NoCurrentUserAccessorException() {
        super("The authz capability is enabled but no CurrentUserAccessor bean exists, so @RequiresPermission would protect nothing.");
    }
}
