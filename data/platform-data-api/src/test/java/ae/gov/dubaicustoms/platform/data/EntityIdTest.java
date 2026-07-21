package ae.gov.dubaicustoms.platform.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityIdTest {

    @Test
    void wrapsAndUnwrapsValue() {
        EntityId<Long> id = EntityId.of(42L);
        assertThat(id.value()).isEqualTo(42L);
    }

    @Test
    void equalityIsByValue() {
        UUID uuid = UUID.randomUUID();
        assertThat(EntityId.of(uuid)).isEqualTo(EntityId.of(uuid));
    }

    @Test
    void nullValueRejected() {
        assertThatNullPointerException().isThrownBy(() -> EntityId.of(null));
    }
}
