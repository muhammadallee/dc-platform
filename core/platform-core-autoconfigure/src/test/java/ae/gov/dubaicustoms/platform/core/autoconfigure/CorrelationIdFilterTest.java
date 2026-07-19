package ae.gov.dubaicustoms.platform.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Behavior of the correlation filter through a real MockMvc filter chain. */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String INCOMING = "0123456789abcdef0123456789abcdef";

    private final CaptureController controller = new CaptureController();
    private MockMvc mockMvc;

    @RestController
    static class CaptureController {

        final AtomicReference<Map<String, String>> seenContext = new AtomicReference<>();
        final AtomicReference<String> seenMdc = new AtomicReference<>();
        final AtomicReference<Boolean> otherThreadSawContext = new AtomicReference<>();

        @GetMapping("/probe")
        String probe() throws Exception {
            seenContext.set(RequestContext.asMap());
            seenMdc.set(MDC.get("correlationId"));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                otherThreadSawContext.set(executor.submit(() -> RequestContext.correlationId().isPresent()).get());
            } finally {
                executor.shutdownNow();
            }
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(HEADER, true))
                .build();
    }

    @AfterEach
    void mdcIsAlwaysCleanAfterTheRequest() {
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void echoesIncomingCorrelationIdAndPopulatesContextDuringRequest() throws Exception {
        mockMvc.perform(get("/probe").header(HEADER, INCOMING))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).isEqualTo(INCOMING));

        assertThat(controller.seenContext.get()).containsEntry("correlationId", INCOMING);
        assertThat(controller.seenMdc.get()).isEqualTo(INCOMING);
    }

    @Test
    void generatesFreshIdWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).matches("[0-9a-f]{32}"));
    }

    @Test
    void regeneratesWhenHeaderMalformed() throws Exception {
        mockMvc.perform(get("/probe").header(HEADER, "not-a-correlation-id"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER))
                        .matches("[0-9a-f]{32}")
                        .isNotEqualTo("not-a-correlation-id"));
    }

    @Test
    void contextDoesNotLeakToOtherThreads() throws Exception {
        mockMvc.perform(get("/probe").header(HEADER, INCOMING)).andExpect(status().isOk());

        assertThat(controller.otherThreadSawContext.get())
                .as("the request context must be confined to the request thread")
                .isFalse();
    }

    @Test
    void passesThroughWithoutContextWhenGenerationDisabled() throws Exception {
        MockMvc noGeneration = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(HEADER, false))
                .build();

        noGeneration.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HEADER)).isNull());

        assertThat(controller.seenContext.get()).isEmpty();
    }
}
