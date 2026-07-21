package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Configuration anchor for the {@code @DataJpaTest} slice: provides the {@code @SpringBootConfiguration}
 * that Boot searches for, and registers this package for entity scanning via
 * {@code @EnableAutoConfiguration}'s auto-configuration package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class SliceTestApp {
}
