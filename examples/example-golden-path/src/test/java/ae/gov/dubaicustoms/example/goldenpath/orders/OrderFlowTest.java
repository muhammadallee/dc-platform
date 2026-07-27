package ae.gov.dubaicustoms.example.goldenpath.orders;

import static ae.gov.dubaicustoms.platform.messaging.testing.EventsAssert.assertThatEvents;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.gov.dubaicustoms.platform.messaging.testing.AutoConfigureTestTransport;
import ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport;
import ae.gov.dubaicustoms.platform.test.junit.PlatformWebTest;
import ae.gov.dubaicustoms.platform.test.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The headline round-trip: an authenticated {@code POST /orders} persists the order (JPA/H2), publishes
 * {@link OrderPlaced} (asserted on the recording {@link TestEventTransport}), and the order is then
 * readable via {@code GET /orders/{id}}. Exercises validation, security, persistence, and messaging in
 * one flow — all through the platform slices, no Docker.
 */
@PlatformWebTest
@AutoConfigureTestTransport
class OrderFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestEventTransport transport;

    @Test
    void placingAnOrderPersistsItAndPublishesAnEvent() throws Exception {
        String location = mvc.perform(post("/orders")
                        .with(TestTokens.user("alice").roles("USER").jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"Acme Corp\",\"amount\":42.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer", is("Acme Corp")))
                .andExpect(jsonPath("$.status", is("PLACED")))
                .andReturn().getResponse().getHeader("Location");

        assertThatEvents(transport).sentTo(OrderPlaced.DESTINATION).withType("OrderPlaced");

        mvc.perform(get(location).with(TestTokens.user("alice").roles("USER").jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer", is("Acme Corp")));
    }
}
