package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.core.context.CorrelationId;
import ae.gov.dubaicustoms.platform.core.context.RequestContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 401/403 problem-body contract (phase-01 R3.1/R3.5, AC13) for every way a request can be refused:
 * no credentials, a REJECTED bearer token (the resource server's own entry point, which used to bypass
 * the platform writer), and an authenticated principal without the authority. The decoder rejects the
 * way Nimbus does (BadJwtException for malformed/untrusted-signature, JwtValidationException for
 * expired), so the real BearerTokenAuthenticationFilter failure path runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = SecurityProblemBodyTest.App.class,
        properties = "dc.platform.errors.type-base-uri=https://problems.example/")
@AutoConfigureMockMvc
class SecurityProblemBodyTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions inTrace(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        // MockMvc runs the filter chain on the calling thread, so this context is the request's.
        try (AutoCloseable scope = RequestContext.open(new CorrelationId(TRACE_ID), Map.of())) {
            return mockMvc.perform(request);
        }
    }

    private static void assertFullContract(ResultActions result, int status, String code, String instance)
            throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://problems.example/" + code))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void missingCredentials() throws Exception {
        ResultActions result = inTrace(get("/secure"));
        assertFullContract(result, 401, "DC-SEC-0401", "/secure");
        result.andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "expired", "untrusted"})
    void rejectedBearerTokensGetTheSameBodyAndNoRejectionReason(String token) throws Exception {
        ResultActions result = inTrace(get("/secure").header("Authorization", "Bearer " + token));
        assertFullContract(result, 401, "DC-SEC-0401", "/secure");
        // The challenge never discloses why the token was rejected.
        result.andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value("Full authentication is required to access this resource"));
    }

    @Test
    void authenticatedPrincipalWithoutTheAuthorityIsForbidden() throws Exception {
        ResultActions result = inTrace(get("/admin").with(jwt().jwt(builder -> builder.subject("alice"))));
        assertFullContract(result, 403, "DC-SEC-0403", "/admin");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        JwtDecoder rejectingDecoder() {
            return token -> {
                if ("expired".equals(token)) {
                    throw new JwtValidationException("Jwt expired at 2020-01-01T00:00:00Z",
                            List.of(new OAuth2Error("invalid_token", "Jwt expired", null)));
                }
                throw new BadJwtException("An error occurred while attempting to decode the Jwt: " + token);
            };
        }

        @RestController
        static class Endpoints {

            @GetMapping("/secure")
            String secure() {
                return "ok";
            }

            @GetMapping("/admin")
            @PreAuthorize("hasAuthority('SCOPE_admin')")
            public String admin() {
                return "admin";
            }
        }
    }
}
