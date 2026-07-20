package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import ae.gov.dubaicustoms.platform.security.SecurityCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boots a real servlet application with the baseline security chain and asserts the phase-06
 * acceptance surface: anonymous 401 with a problem body, an authenticated (mock) JWT reaching the
 * controller, and a SecurityCustomizer-permitted path bypassing authentication.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = PlatformSecurityWebTest.App.class,
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.test/jwks.json")
@AutoConfigureMockMvc
class PlatformSecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousRequestIsUnauthorizedWithProblemBody() throws Exception {
        mockMvc.perform(get("/secure"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void authenticatedJwtReachesTheControllerAndPopulatesCurrentUser() throws Exception {
        mockMvc.perform(get("/secure").with(jwt().jwt(builder -> builder.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(content().string("alice"));
    }

    @Test
    void customizerPermittedPathBypassesAuthentication() throws Exception {
        // /custom is opened only by App's SecurityCustomizer bean; a 404 (no such mapping reached
        // the DispatcherServlet) proves the filter chain let the anonymous request through.
        mockMvc.perform(get("/custom")).andExpect(status().isNotFound());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @RestController
        static class SecureController {

            private final CurrentUserAccessor currentUserAccessor;

            SecureController(CurrentUserAccessor currentUserAccessor) {
                this.currentUserAccessor = currentUserAccessor;
            }

            @GetMapping("/secure")
            String secure() {
                return currentUserAccessor.currentUser().map(u -> u.subject()).orElse("anonymous");
            }
        }

        @Bean
        @Order(1)
        SecurityCustomizer permitCustomPath() {
            return http -> http.authorizeHttpRequests(auth -> auth.requestMatchers("/custom").permitAll());
        }
    }
}
