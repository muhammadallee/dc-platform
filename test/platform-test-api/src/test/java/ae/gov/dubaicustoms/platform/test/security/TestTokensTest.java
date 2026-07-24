package ae.gov.dubaicustoms.platform.test.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

class TestTokensTest {

    @Test
    void buildsANonNullJwtPostProcessorFromSubjectRolesAuthoritiesAndClaims() {
        // Exercises every builder step; jwt() assembles the token (subject + claims + authorities).
        JwtRequestPostProcessor postProcessor = TestTokens.user("alice")
                .roles("ADMIN")
                .authority("SCOPE_orders.read")
                .claim("tenant", "dxb")
                .jwt();

        assertThat(postProcessor).isNotNull();
        // Applying it to a request must not throw and returns a (post-processed) request.
        assertThatCode(() -> postProcessor.postProcessRequest(new MockHttpServletRequest()))
                .doesNotThrowAnyException();
    }

    @Test
    void builderStepsAreChainable() {
        TestTokens.Builder builder = TestTokens.user("bob");
        assertThat(builder.roles("VIEWER")).isSameAs(builder);
        assertThat(builder.authority("SCOPE_read")).isSameAs(builder);
        assertThat(builder.claim("tenant", "auh")).isSameAs(builder);
        assertThat(builder.jwt()).isNotNull();
    }
}
