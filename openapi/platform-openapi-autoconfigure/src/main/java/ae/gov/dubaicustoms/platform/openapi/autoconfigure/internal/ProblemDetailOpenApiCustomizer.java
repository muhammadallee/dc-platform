package ae.gov.dubaicustoms.platform.openapi.autoconfigure.internal;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Registers a reusable {@code ProblemDetail} schema and appends the platform's default 4xx/5xx
 * problem responses to every documented operation — every service on the platform maps errors
 * through {@code PlatformExceptionHandler} (errors slice), so every operation truthfully
 * documents the same shape without each team repeating it.
 *
 * <p>Status codes mirror the errors-slice registry: 400 (validation), 401/403 (arrive with the
 * phase-6 security baseline; documented now so specs do not need to change later), 404, 409, 422
 * (the {@code BusinessException} default), and 500.
 */
public class ProblemDetailOpenApiCustomizer implements OpenApiCustomizer {

    /** Schema name referenced by every appended response; matches the errors-slice model. */
    static final String SCHEMA_NAME = "ProblemDetail";

    private static final List<String> DOCUMENTED_STATUSES =
            List.of("400", "401", "403", "404", "409", "422", "500");

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents() != null ? openApi.getComponents() : new Components();
        components.addSchemas(SCHEMA_NAME, problemDetailSchema());
        openApi.setComponents(components);

        if (openApi.getPaths() == null) {
            return;
        }
        Content problemContent = new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + SCHEMA_NAME)));
        for (PathItem pathItem : openApi.getPaths().values()) {
            for (io.swagger.v3.oas.models.Operation operation : pathItem.readOperations()) {
                ApiResponses responses = operation.getResponses() != null
                        ? operation.getResponses() : new ApiResponses();
                for (String status : DOCUMENTED_STATUSES) {
                    responses.putIfAbsent(status,
                            new ApiResponse().description(defaultDescription(status)).content(problemContent));
                }
                operation.setResponses(responses);
            }
        }
    }

    private static Schema<?> problemDetailSchema() {
        // Mirrors org.springframework.http.ProblemDetail plus the platform's extensions
        // (errors-autoconfigure: code, correlationId, timestamp; errors[] on validation failures).
        Map<String, Schema> properties = Map.of(
                "type", new StringSchema(),
                "title", new StringSchema(),
                "status", new Schema<Integer>().type("integer"),
                "detail", new StringSchema(),
                "instance", new StringSchema(),
                "code", new StringSchema(),
                "correlationId", new StringSchema(),
                "timestamp", new StringSchema().format("date-time"));
        return new Schema<>().type("object").properties(properties);
    }

    private static String defaultDescription(String status) {
        return switch (status) {
            case "400" -> "Bad Request — validation failure (RFC 9457 ProblemDetail, errors[])";
            case "401" -> "Unauthorized (RFC 9457 ProblemDetail)";
            case "403" -> "Forbidden (RFC 9457 ProblemDetail)";
            case "404" -> "Not Found (RFC 9457 ProblemDetail)";
            case "409" -> "Conflict (RFC 9457 ProblemDetail)";
            case "422" -> "Unprocessable Content — business-rule violation (RFC 9457 ProblemDetail)";
            default -> "Internal Server Error (RFC 9457 ProblemDetail)";
        };
    }
}
