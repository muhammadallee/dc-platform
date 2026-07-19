package ae.gov.dubaicustoms.platform.cycb;

import ae.gov.dubaicustoms.platform.cyca.CycA;

// Fixture: other half of the capability-level cycle.
public final class CycB {
    public CycA other() {
        return new CycA();
    }
}
