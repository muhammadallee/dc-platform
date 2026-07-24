package ${package};

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.test.junit.PlatformWebTest;
import ae.gov.dubaicustoms.platform.test.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /hello}. {@code @PlatformWebTest} boots the platform web stack
 * (errors, validation, security) with an auto-configured {@link MockMvc}; authenticate requests with
 * {@link TestTokens}. Endpoints are authenticated by default, so the unauthenticated call is rejected.
 */
@PlatformWebTest
class HelloControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void greetsAuthenticatedUser() throws Exception {
        mvc.perform(get("/hello").param("name", "alice")
                        .with(TestTokens.user("alice").roles("USER").jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hello, alice")));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(get("/hello")).andExpect(status().isUnauthorized());
    }
}
