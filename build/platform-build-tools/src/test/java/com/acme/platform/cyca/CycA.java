package com.acme.platform.cyca;

import com.acme.platform.cycb.CycB;

// Fixture: half of a capability-level cycle (rule 4 violation with CycB).
public final class CycA {
    public CycB other() {
        return new CycB();
    }
}
