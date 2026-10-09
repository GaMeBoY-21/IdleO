package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ControlPlaneTest
class TemplateApiTests {

    @Autowired
    MockMvc mvc;

    @Test
    void signedInUsersSeeTheBundledCatalog() throws Exception {
        var user = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/templates").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[?(@.id == 'cuda-smoke-test')].minVramMb")
                        .value(1024))
                .andExpect(jsonPath("$.templates[?(@.id == 'cuda-smoke-test')].maxRuntimeCapSeconds")
                        .value(900))
                .andExpect(jsonPath("$.templates[?(@.id == 'cuda-smoke-test')].description")
                        .isNotEmpty())
                .andExpect(jsonPath("$.templates[?(@.id == 'cuda-smoke-test')].params[0]")
                        .doesNotExist())
                .andExpect(jsonPath("$.templates[?(@.id == 'cuda-smoke-test')].inputs[0]")
                        .doesNotExist());
        mvc.perform(get("/v1/templates")).andExpect(status().isUnauthorized());
    }

    /**
     * Templates come grouped into services, each one tile on Home (ADR 0021), in the catalog's order: the development
     * catalog's simulated production services, then the test jobs.
     */
    @Test
    void templatesComeWithTheirServices() throws Exception {
        var user = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/templates").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[*].id")
                        .value(org.hamcrest.Matchers.contains("fine-tune", "run-code", "developer-tests")))
                .andExpect(jsonPath("$.services[0].name").value("Fine-tune a model"))
                .andExpect(jsonPath("$.services[0].runningLabel").value("Fine-tuning"))
                .andExpect(jsonPath("$.services[0].icon").value("layers"))
                .andExpect(jsonPath("$.services[2].name").value("Test jobs"))
                .andExpect(jsonPath("$.services[2].runningLabel").value("Running a test job"))
                .andExpect(jsonPath("$.services[2].icon").value("flask"))
                .andExpect(jsonPath("$.services[2].hidden").value(false))
                .andExpect(jsonPath("$.templates[?(@.id == 'fine-tune-simulated')].service")
                        .value("fine-tune"))
                .andExpect(jsonPath("$.templates[?(@.id == 'cuda-smoke-test')].service")
                        .value("developer-tests"));
    }

    /** Each template describes its parameters and input once, for every surface to build its form from (ADR 0019). */
    @Test
    void templatesDescribeTheirParametersAndInput() throws Exception {
        var user = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/templates").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].typicalRuntimeSeconds")
                        .value(20))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[0].name")
                        .value("greeting"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[0].type")
                        .value("string"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[0].required")
                        .value(true))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[0].maxLength")
                        .value(200))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[1].name")
                        .value("repeat"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[1].defaultValue")
                        .value(1))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[1].min")
                        .value(1))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[1].max")
                        .value(5))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[0].group")
                        .value("basic"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].params[1].group")
                        .value("advanced"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].inputs[0].name")
                        .value("file"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].inputs[0].kind")
                        .value("file"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].inputs[0].required")
                        .value(true))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].inputs[0].upload")
                        .value(true))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].inputs[0].link")
                        .value(true))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].inputs[0].maxSizeBytes")
                        .value(512 * 1024 * 1024))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].questions[0].kind")
                        .value("param"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].questions[0].param")
                        .value("greeting"))
                .andExpect(jsonPath("$.templates[?(@.id == 'echo-input')].questions[0].label")
                        .value("Greeting"));
    }

    /** A service's template asks plain questions: its inputs, a preset, and budget chips (ADR 0022). */
    @Test
    void theFineTuneAsksForAModelDataAndHowThorough() throws Exception {
        var user = TestUsers.signUp(mvc);
        var fineTune = "$.templates[?(@.id == 'fine-tune-simulated')]";

        mvc.perform(get("/v1/templates").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(fineTune + ".inputs[*].name").value(org.hamcrest.Matchers.contains("model", "data")))
                .andExpect(jsonPath(fineTune + ".inputs[0].kind").value("folder"))
                .andExpect(jsonPath(fineTune + ".inputs[0].maxSizeBytes").value(40L << 30))
                .andExpect(jsonPath(fineTune + ".questions[0].kind").value("preset"))
                .andExpect(jsonPath(fineTune + ".questions[0].name").value("thoroughness"))
                .andExpect(jsonPath(fineTune + ".questions[0].defaultOption").value("standard"))
                .andExpect(
                        jsonPath(fineTune + ".questions[0].options[0].minutes").value(15))
                .andExpect(jsonPath(fineTune + ".questions[0].options[0].sets.epochs")
                        .value(1))
                .andExpect(jsonPath(fineTune + ".params[*].group")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("advanced"))))
                .andExpect(jsonPath(fineTune + ".budgetChips[*]")
                        .value(org.hamcrest.Matchers.contains(5000, 10000, 50000)))
                .andExpect(jsonPath(fineTune + ".defaultBudgetPaise").value(10000))
                .andExpect(jsonPath("$.templates[?(@.id == 'run-code-simulated')].questions[1].kind")
                        .value("gpu_memory"))
                .andExpect(jsonPath("$.templates[?(@.id == 'run-code-simulated')].questions[1].choicesGb[*]")
                        .value(org.hamcrest.Matchers.contains(8, 12, 16, 24)));
    }
}
