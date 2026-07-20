package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ImmediateDispatcherTest {

    @Test
    void runsTheActionImmediately() {
        var dispatcher = new ImmediateDispatcher();
        AtomicBoolean ran = new AtomicBoolean();

        dispatcher.dispatch(() -> ran.set(true));

        assertThat(ran).isTrue();
    }
}
