package ae.gov.dubaicustoms.platform.test.arch.fixtures.clean;

// A conformant service class: no broker templates, no hand-rolled advice, no System.getenv.
// Used by PlatformUsageRulesTest to prove the rules PASS on well-behaved code.
public class CleanController {

    public String hello(String name) {
        return "hello, " + name;
    }
}
