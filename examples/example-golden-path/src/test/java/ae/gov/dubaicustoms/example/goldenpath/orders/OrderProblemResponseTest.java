package ae.gov.dubaicustoms.example.goldenpath.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.test.junit.PlatformWebTest;
import ae.gov.dubaicustoms.platform.test.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the platform's error contract on the orders API: an unknown order is a 404 problem carrying
 * the service's error code, and an invalid create body is a 400 problem with a per-field
 * {@code errors[]} extension — both {@code application/problem+json}, neither handled by any code in
 * this service.
 */
@PlatformWebTest
class OrderProblemResponseTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unknownOrderIsRfc9457NotFound() throws Exception {
        mvc.perform(get("/orders/9999").with(TestTokens.user("alice").roles("USER").jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DC-XMPL-0404"));
    }

    @Test
    void invalidCreateBodyIsRfc9457BadRequest() throws Exception {
        mvc.perform(post("/orders")
                        .with(TestTokens.user("alice").roles("USER").jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
