package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;

class FlywayPresenceCheckTest {

    private final ClassLoader withFlyway = getClass().getClassLoader();
    private final ClassLoader withoutFlyway = new FilteredClassLoader(Flyway.class);

    @Test
    void passesWhenFlywayPresent() {
        assertThatCode(() -> new FlywayPresenceCheck(true, withFlyway).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void failsWhenRequiredButFlywayAbsent() {
        assertThatThrownBy(() -> new FlywayPresenceCheck(true, withoutFlyway).afterPropertiesSet())
                .isInstanceOf(MissingFlywayException.class)
                .hasMessageContaining("require-migrations=false");
    }

    @Test
    void passesWhenNotRequiredEvenIfFlywayAbsent() {
        assertThatCode(() -> new FlywayPresenceCheck(false, withoutFlyway).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
