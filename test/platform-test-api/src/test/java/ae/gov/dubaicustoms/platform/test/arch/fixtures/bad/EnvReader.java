package ae.gov.dubaicustoms.platform.test.arch.fixtures.bad;

// Violates noSystemGetenv: reads configuration straight from the environment instead of the
// platform secrets property source. PlatformUsageRulesTest asserts the rule flags this class.
public class EnvReader {

    public String secret() {
        return System.getenv("DB_PASSWORD");
    }
}
