package ae.gov.dubaicustoms.platform.delta;

import org.springframework.beans.factory.annotation.Autowired;

// Fixture: field injection (rule 3 violation).
public final class DeltaFieldInjected {
    @Autowired
    private Runnable dependency;

    public Runnable dependency() {
        return dependency;
    }
}
