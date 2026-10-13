package com.idleo.controlplane;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * The API contract (ADR 0018): what springdoc can't see from the controllers alone. Every operation needs the
 * caller's ID token as a bearer token, and every error is an RFC 9457 problem detail; {@code Idempotency-Key} is
 * required on the create requests that take it, though the controllers read it as optional to answer its absence
 * with a problem detail of their own. The document has no server URL, so it doesn't change with the port that
 * generated it.
 */
@Configuration(proxyBeanMethods = false)
class ApiContract {

    static final String BEARER = "idToken";
    static final String ADMIN_SESSION = "adminSession";
    static final String PROBLEM = "ProblemDetail";
    static final String PROBLEM_JSON = "application/problem+json";

    /**
     * Stable names for the generated client: an operation is its controller, less "Controller", and its method,
     * for example {@code sessionCreate}, tagged with the controller's name.
     */
    @Bean
    OperationCustomizer apiOperationNames() {
        return (operation, handler) -> {
            var controller = handler.getBeanType().getSimpleName().replaceFirst("Controller$", "");
            var prefix = Character.toLowerCase(controller.charAt(0)) + controller.substring(1);
            var method = handler.getMethod().getName();
            operation.setOperationId(prefix + Character.toUpperCase(method.charAt(0)) + method.substring(1));
            operation.setTags(List.of(prefix));
            return operation;
        };
    }

    /** Record fields are required unless {@code @Nullable}, and a nullable one may be {@code null}. */
    @Bean
    ModelConverter recordFields() {
        return new RecordFields();
    }

    @Bean
    OpenApiCustomizer apiContractCustomizer() {
        return openApi -> {
            openApi.setInfo(new Info()
                    .title("IdleO control plane API")
                    .version("v1")
                    .description("The renter and host API. Money is integer paise; times are UTC. Every request "
                            + "needs the caller's ID token from the identity provider as a bearer token."));
            openApi.setServers(List.of(new Server().url("/")));
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents()
                    .addSecuritySchemes(
                            BEARER,
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.HTTP)
                                    .scheme("bearer")
                                    .bearerFormat("JWT")
                                    .description("An ID token from a trusted issuer (ADR 0009)."))
                    .addSecuritySchemes(
                            ADMIN_SESSION,
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.APIKEY)
                                    .in(SecurityScheme.In.HEADER)
                                    .name("Admin-Session")
                                    .description("An admin session from POST /admin/v1/session (ADR 0027), sent beside "
                                            + "the ID token of the admin who started it."))
                    .addSchemas(PROBLEM, problemDetail());
            openApi.setSecurity(List.of(new SecurityRequirement().addList(BEARER)));
            if (openApi.getPaths() == null) {
                return;
            }
            // An admin call needs an admin session too, unless its controller says it needs only the ID token: the
            // second factor's own calls, which start one.
            openApi.getPaths()
                    .forEach((name, path) -> path.readOperations().stream()
                            .filter(operation -> name.startsWith("/admin/v1/") && operation.getSecurity() == null)
                            .forEach(operation -> operation.setSecurity(List.of(
                                    new SecurityRequirement().addList(BEARER).addList(ADMIN_SESSION)))));
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(operation -> {
                        operation
                                .getResponses()
                                .addApiResponse(
                                        "default",
                                        new ApiResponse()
                                                .description("An error, as an RFC 9457 problem detail")
                                                .content(new Content()
                                                        .addMediaType(
                                                                PROBLEM_JSON,
                                                                new MediaType()
                                                                        .schema(new Schema<>()
                                                                                .$ref("#/components/schemas/"
                                                                                        + PROBLEM)))));
                        if (operation.getParameters() != null) {
                            operation.getParameters().stream()
                                    .filter(parameter -> "header".equals(parameter.getIn())
                                            && IdempotentRequests.HEADER.equals(parameter.getName()))
                                    .forEach(parameter -> parameter
                                            .required(true)
                                            .description("A unique key for this create request, 1 to 255 visible ASCII "
                                                    + "characters. A repeat returns the first response; the same key with a "
                                                    + "different body is refused."));
                            operation.getParameters().stream()
                                    .filter(parameter -> "header".equals(parameter.getIn())
                                            && HttpHeaders.IF_MATCH.equalsIgnoreCase(parameter.getName()))
                                    .forEach(parameter -> parameter
                                            .required(true)
                                            .description(
                                                    "The ETag from the last read of this resource; 428 without it, "
                                                            + "412 when it's stale."));
                        }
                    }));
        };
    }

    /** RFC 9457's members, plus {@code errors} for invalid fields. */
    private static ObjectSchema problemDetail() {
        var fieldError = new ObjectSchema();
        fieldError.addProperty("field", new StringSchema());
        fieldError.addProperty("detail", new StringSchema());
        var errors = new ArraySchema();
        errors.setItems(fieldError);
        errors.setDescription("For a 400 with invalid fields: each field and what's wrong with it");
        var problem = new ObjectSchema();
        problem.setDescription("An RFC 9457 problem detail (ADR 0019)");
        problem.setRequired(List.of("type", "title", "status"));
        var type = new StringSchema();
        type.format("uri-reference");
        type.setDescription("What kind of error it is: one of the types in docs/api/problems.md, /problems/<slug>. "
                + "Clients branch on this, never on title or detail.");
        type.setEnum(Arrays.stream(Problem.values())
                .map(entry -> entry.type().toString())
                .toList());
        problem.addProperty("type", type);
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new IntegerSchema());
        problem.addProperty("detail", new StringSchema());
        problem.addProperty("instance", new StringSchema().format("uri"));
        problem.addProperty("errors", errors);
        return problem;
    }
}
