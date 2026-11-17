package spikes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S2: Spring Security 7 applies a {@code Customizer<HttpSecurity>} bean to Spring Boot's default
 * {@code SecurityFilterChain}, so a service can adjust the platform's chain without a platform type.
 * Expectation: "/open" is permitted by the customizer bean; "/closed" still needs authentication.
 */
@SpringBootTest(classes = S2CustomizerHttpSecurityTest.App.class)
@AutoConfigureMockMvc
class S2CustomizerHttpSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Endpoints.class)
    static class App {
        @Bean
        Customizer<HttpSecurity> openPath() {
            return http -> http.authorizeHttpRequests(a -> a.requestMatchers("/open").permitAll());
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/open")
        String open() {
            return "open";
        }

        @GetMapping("/closed")
        String closed() {
            return "closed";
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void customizerBeanOpensOnePath() throws Exception {
        mvc.perform(get("/open")).andExpect(status().isOk());
    }

    @Test
    void defaultChainStillProtectsTheRest() throws Exception {
        mvc.perform(get("/closed")).andExpect(status().isUnauthorized());
    }
}
