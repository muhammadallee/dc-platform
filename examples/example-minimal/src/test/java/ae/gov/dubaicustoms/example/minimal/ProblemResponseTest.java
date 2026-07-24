package ae.gov.dubaicustoms.example.minimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the floor's headline promise: a thrown {@link ae.gov.dubaicustoms.platform.errors.NotFoundException}
 * becomes an RFC-9457 {@code application/problem+json} 404 carrying the error code — produced entirely
 * by the platform, since this service registers no exception handler of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProblemResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingWidgetRendersAsRfc9457Problem() throws Exception {
        mockMvc.perform(get("/widgets/42"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DC-XMPL-0404"));
    }

    @Test
    void pingReturnsOk() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
