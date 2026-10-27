package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SanitizedMessageViewTest {

    @Test
    void aContainerHoldingItselfEndsTheDescentMasked() {
        List<Object> list = new ArrayList<>();
        list.add("ok");
        list.add(list);
        list.add(list);
        Object clean = SanitizedMessageView.sanitizedTree("payload", list, 0);
        assertThat(clean).isEqualTo(List.of("ok", PlatformValueMasker.MASK, PlatformValueMasker.MASK));
    }

    @Test
    void aSharedButAcyclicValueIsTraversedAtEachPlace() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("password", "hunter2");
        Object clean = SanitizedMessageView.sanitizedTree("payload", List.of(shared, shared), 0);
        assertThat(clean).isEqualTo(List.of(Map.of("password", "****"), Map.of("password", "****")));
    }
}
