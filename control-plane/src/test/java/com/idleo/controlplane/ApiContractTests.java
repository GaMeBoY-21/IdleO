package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The API contract is a file (ADR 0018): the OpenAPI document generated from the controllers must equal the
 * committed {@code api/openapi.yaml}, so every API change shows up in review as a diff to that file. After an
 * intended change, rewrite it with {@code ./mvnw test -Dtest=ApiContractTests -Dopenapi.write=true}.
 */
@ControlPlaneTest
class ApiContractTests {

    static final Path COMMITTED = Path.of("..", "api", "openapi.yaml");

    @Autowired
    MockMvc mvc;

    private String generated() throws Exception {
        var user = TestUsers.signUp(mvc);
        return mvc.perform(get("/v3/api-docs.yaml").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theGeneratedContractMatchesTheCommittedFile() throws Exception {
        var generated = generated();
        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(COMMITTED, generated);
        }
        assertThat(Files.exists(COMMITTED)).as(COMMITTED + " exists").isTrue();
        assertThat(generated)
                .as("api/openapi.yaml is out of date; run ./mvnw test -Dtest=ApiContractTests -Dopenapi.write=true "
                        + "and review the diff")
                .isEqualTo(Files.readString(COMMITTED));
    }

    @Test
    void theContractDescribesOnlyV1WithItsHeadersAndProblemDetails() throws Exception {
        var contract = generated();

        assertThat(contract).startsWith("openapi: 3.1");
        assertThat(contract.lines().filter(line -> line.matches("^  /[^:]*:$")))
                .as("only /v1 paths, never the actuator")
                .isNotEmpty()
                .allMatch(line -> line.startsWith("  /v1/"));
        assertThat(contract)
                .contains("name: Idempotency-Key")
                .contains("name: If-Match")
                .contains("            ETag:")
                .contains("application/problem+json")
                .contains("bearerFormat: JWT")
                .doesNotContain("/actuator")
                .doesNotContain("http://localhost");
    }
}
