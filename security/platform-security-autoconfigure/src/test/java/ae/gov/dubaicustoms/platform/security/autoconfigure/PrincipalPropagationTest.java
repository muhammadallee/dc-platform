package ae.gov.dubaicustoms.platform.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The principal crosses a context-propagating executor, and does not stay behind on the thread afterwards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = PrincipalPropagationTest.App.class, properties = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.test/jwks.json",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.test/",
    "spring.security.oauth2.resourceserver.jwt.audiences=svc"})
@AutoConfigureMockMvc
class PrincipalPropagationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void thePrincipalCrossesTheExecutorAndIsClearedAfterTheTask() throws Exception {
        mockMvc.perform(get("/across").with(jwt().jwt(token -> token.subject("alice")))).andExpect(status().isOk());
        assertThat(Api.seen).isEqualTo("alice");
        assertThat(Api.afterTask).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @RestController
    static class Api {

        static volatile String seen;
        static volatile boolean afterTask;

        private final CurrentUserAccessor users;
        private final ExecutorService single = Executors.newSingleThreadExecutor();

        Api(CurrentUserAccessor users) {
            this.users = users;
        }

        @GetMapping("/across")
        void across() throws Exception {
            ExecutorService propagating = ContextExecutorService.wrap(single, ContextSnapshotFactory.builder().build()::captureAll);
            seen = propagating.submit(() -> users.currentUser().map(CurrentUser::subject).orElse("none")).get();
            afterTask = single.submit(() -> users.currentUser().isEmpty()).get();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Api.class)
    static class App {
    }
}
