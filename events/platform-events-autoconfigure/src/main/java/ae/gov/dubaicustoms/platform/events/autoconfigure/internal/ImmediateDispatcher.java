package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

/** Default {@link AfterCommitDispatcher}: always runs the action immediately. */
public final class ImmediateDispatcher implements AfterCommitDispatcher {

    @Override
    public void dispatch(Runnable action) {
        action.run();
    }
}
