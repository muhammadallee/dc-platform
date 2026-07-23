package ae.gov.dubaicustoms.platform.storage.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;

/**
 * Implemented by the platform's {@code ObjectStore} decorators so the capability descriptor can unwrap
 * them to the underlying provider and report its name.
 */
public interface DelegatingObjectStore extends ObjectStore {

    /** The wrapped store. */
    ObjectStore delegate();
}
