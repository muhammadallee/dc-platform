package com.acme.platform.cycb;

import com.acme.platform.cyca.CycA;

// Fixture: other half of the capability-level cycle.
public final class CycB {
    public CycA other() {
        return new CycA();
    }
}
