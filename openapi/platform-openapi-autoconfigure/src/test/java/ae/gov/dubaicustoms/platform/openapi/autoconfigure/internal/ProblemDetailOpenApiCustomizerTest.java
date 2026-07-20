package ae.gov.dubaicustoms.platform.openapi.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

/** Unit test for the ProblemDetail-schema and default-response appending behavior. */
class ProblemDetailOpenApiCustomizerTest {

    private final ProblemDetailOpenApiCustomizer customizer = new ProblemDetailOpenApiCustomizer();

    @Test
    void registersTheProblemDetailSchema() {
        OpenAPI openApi = new OpenAPI();
        customizer.customise(openApi);

        assertThat(openApi.getComponents().getSchemas())
                .containsKey(ProblemDetailOpenApiCustomizer.SCHEMA_NAME);
    }

    @Test
    void appendsDefaultResponsesToEveryOperation() {
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/orders/{id}",
                new PathItem().get(new Operation().responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("OK"))))));

        customizer.customise(openApi);

        ApiResponses responses = openApi.getPaths().get("/orders/{id}").getGet().getResponses();
        assertThat(responses).containsKeys("200", "400", "401", "403", "404", "409", "422", "500");
        assertThat(responses.get("404").getContent().get("application/problem+json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ProblemDetail");
    }

    @Test
    void doesNotOverwriteAnOperationDefinedStatus() {
        ApiResponse custom = new ApiResponse().description("custom 404");
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/widgets",
                new PathItem().get(new Operation().responses(
                        new ApiResponses().addApiResponse("404", custom)))));

        customizer.customise(openApi);

        assertThat(openApi.getPaths().get("/widgets").getGet().getResponses().get("404"))
                .isSameAs(custom);
    }

    @Test
    void toleratesNoPaths() {
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        assertThat(openApi.getComponents().getSchemas()).isNotEmpty();
    }
}
