package ae.gov.dubaicustoms.example.goldenpath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.test.junit.PlatformWebTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the cross-cutting surface the golden path is supposed to expose out of the box, on the
 * permitted (unauthenticated) paths: the OpenAPI document is served, and {@code /actuator/platform}
 * reports the capabilities this service wires. The capability report is what the smoke matrix asserts
 * against its expectation table.
 */
@PlatformWebTest
class PlatformSurfaceTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }

    @Test
    void platformActuatorReportsWiredCapabilities() throws Exception {
        mvc.perform(get("/actuator/platform"))
                .andExpect(status().isOk())
                // The endpoint returns a JSON array of {name,status,detail}; messaging is a capability
                // this service wires. This is the surface the smoke matrix asserts against.
                .andExpect(jsonPath("$[?(@.name=='messaging')]").exists());
    }
}
