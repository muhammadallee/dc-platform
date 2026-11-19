package ${package};

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.test.junit.PlatformWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /hello}. {@code @PlatformWebTest} boots the platform web stack
 * (errors, validation) with an auto-configured {@link MockMvc}.
 */
@PlatformWebTest
class HelloControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void greetsTheCaller() throws Exception {
        mvc.perform(get("/hello").param("name", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hello, alice")));
    }
}
