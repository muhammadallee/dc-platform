package ae.gov.dubaicustoms.platform.test.arch.fixtures.bad;

// Non-conformant: blocks the thread with Thread.sleep instead of platform scheduling/resilience.
public class Sleeper {

    public void pause() throws InterruptedException {
        Thread.sleep(10L);
    }
}
