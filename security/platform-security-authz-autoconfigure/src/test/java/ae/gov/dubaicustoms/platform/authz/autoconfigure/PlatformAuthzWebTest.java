package ae.gov.dubaicustoms.platform.authz.autoconfigure;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.authz.RequiresPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boots a real servlet application with the security baseline AND the authz advisor and asserts
 * the phase-06 acceptance surface: anonymous 401, authenticated-without-permission 403,
 * authenticated-with-permission 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = PlatformAuthzWebTest.App.class,
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.test/jwks.json")
@AutoConfigureMockMvc
class PlatformAuthzWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedWithoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/orders").with(jwt().jwt(b -> b.subject("bob").claim("roles", java.util.List.of("orders:write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedWithPermissionSucceeds() throws Exception {
        mockMvc.perform(get("/orders").with(jwt().jwt(b -> b.subject("alice").claim("roles", java.util.List.of("orders:read")))))
                .andExpect(status().isOk());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @RestController
        static class OrdersController {

            @RequiresPermission("orders:read")
            @GetMapping("/orders")
            String orders() {
                return "orders";
            }
        }
    }
}
