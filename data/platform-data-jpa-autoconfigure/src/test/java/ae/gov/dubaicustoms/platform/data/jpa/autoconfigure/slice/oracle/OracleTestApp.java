package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Configuration anchor for the Oracle container tests: the nearest {@code @SpringBootConfiguration}
 * for this package, so only the entities here are scanned (the H2 slice entities one package up stay
 * out of the Oracle persistence unit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class OracleTestApp {
}
