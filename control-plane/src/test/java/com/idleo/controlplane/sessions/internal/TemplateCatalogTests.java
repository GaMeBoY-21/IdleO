package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.sessions.Template;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The template catalog is refused whole if anything in it is wrong (ADR 0015). */
class TemplateCatalogTests {

    private static final String DIGEST = "@sha256:" + "a".repeat(64);

    private static String entry(String overrides) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("id", "smoke");
        fields.put("service", "tests");
        fields.put("name", "Smoke");
        fields.put("description", "Prints the GPU.");
        fields.put("image", "registry.example/smoke" + DIGEST);
        fields.put("min_vram_mb", "1024");
        fields.put("default_max_runtime", "5m");
        fields.put("max_runtime_cap", "15m");
        fields.put("memory_mb", "2048");
        fields.put("cpu_millicores", "2000");
        fields.put("pids_limit", "256");
        fields.put("scratch_mb", "1024");
        fields.put("checkpoint_interval", "5m");
        for (var override : overrides.split(";")) {
            if (override.isBlank()) {
                continue;
            }
            var parts = override.split("=", 2);
            if (parts[1].equals("<none>")) {
                fields.remove(parts[0]);
            } else {
                fields.put(parts[0], parts[1]);
            }
        }
        var yaml = new StringBuilder();
        fields.forEach((key, value) -> yaml.append(yaml.isEmpty() ? "  - " : "    ")
                .append(key)
                .append(": ")
                .append(value)
                .append('\n'));
        return yaml.toString();
    }

    private static final String SERVICES = """
            services:
              - id: tests
                name: Tests
                tagline: for the tests
                running_label: Testing
                icon: flask
            """;

    private static java.util.Map<String, com.idleo.controlplane.sessions.Template> parse(String entries) {
        return parse(SERVICES, entries);
    }

    private static java.util.Map<String, com.idleo.controlplane.sessions.Template> parse(
            String services, String entries) {
        return TemplateCatalog.parse(
                new ByteArrayInputStream((services + "templates:\n" + entries).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theRealCatalogLoadsAndPinsEveryImage() throws Exception {
        try (var in = Files.newInputStream(Path.of("../templates/catalog.yaml"))) {
            var templates = TemplateCatalog.parse(in);
            var smoke = templates.get("cuda-smoke-test");
            assertThat(smoke).isNotNull();
            assertThat(smoke.image()).contains("@sha256:");
            assertThat(smoke.args()).containsExactly("nvidia-smi");
            assertThat(smoke.defaultMaxRuntime()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Test
    void theDevelopmentCatalogRunsTheSmokeTestWithTheDevelopmentRunner() throws Exception {
        try (var in = Files.newInputStream(Path.of("../templates/development.yaml"))) {
            var smoke = TemplateCatalog.parse(in).get("cuda-smoke-test");
            assertThat(smoke.args()).containsExactly("--duration", "20s");
            assertThat(smoke.checkpointInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(smoke.image()).contains("@sha256:");
        }
    }

    @Test
    void aWellFormedEntryLoads() {
        assertThat(parse(entry("")).get("smoke").minVramMb()).isEqualTo(1024);
    }

    @Test
    void anImageWithoutADigestIsRefused() {
        assertThatThrownBy(() -> parse(entry("image=registry.example/smoke:latest")))
                .hasMessageContaining("pinned by digest");
    }

    @Test
    void malformedEntriesAreRefused() {
        for (var overrides : new String[] {
            "id=Smoke Test",
            "name=<none>",
            "min_vram_mb=0",
            "memory_mb=lots",
            "default_max_runtime=1h",
            "checkpoint_interval=soon",
            "max_runtime_cap=<none>",
            "description=<none>",
            "typical_runtime=soon",
            "colour=blue"
        }) {
            assertThatThrownBy(() -> parse(entry(overrides))).as(overrides).isInstanceOf(IllegalStateException.class);
        }
    }

    private static final String ECHO_PARAMS = """
                params:
                  - name: greeting
                    label: Greeting
                    type: string
                    required: true
                    max_length: 200
                  - name: repeat
                    label: Repeat
                    help: How many times.
                    type: integer
                    default: 1
                    min: 1
                    max: 5
                  - name: rate
                    label: Learning rate
                    type: number
                    default: 0.0002
                  - name: mode
                    label: Mode
                    type: choice
                    choices: [fast, careful]
                    default: careful
                input:
                  accepts: required
                  upload: true
                  url: true
                  max_size_mb: 512
                  label: Training data
            """;

    /** An entry with its params and input written as YAML under it. */
    private static String withBlocks(String blocks) {
        var lines = blocks.lines().filter(line -> !line.isBlank()).toList();
        int indent = lines.stream()
                .mapToInt(line -> line.length() - line.stripLeading().length())
                .min()
                .orElse(0);
        return entry("")
                + lines.stream()
                        .map(line -> "    " + line.substring(indent) + "\n")
                        .reduce("", String::concat);
    }

    @Test
    void parametersAndAnInputLoadWithTheirDefaultsChecked() {
        var template = parse(withBlocks(ECHO_PARAMS)).get("smoke");

        assertThat(template.params())
                .extracting(Template.Param::name)
                .containsExactly("greeting", "repeat", "rate", "mode");
        var repeat = template.params().get(1);
        assertThat(repeat.type()).isEqualTo(Template.ParamType.INTEGER);
        assertThat(repeat.defaultValue()).isEqualTo(1L);
        assertThat(repeat.min()).isEqualByComparingTo("1");
        assertThat(template.params().get(2).defaultValue()).isEqualTo(new java.math.BigDecimal("0.0002"));
        assertThat(template.params().get(3).choices()).containsExactly("fast", "careful");
        assertThat(template.input())
                .isEqualTo(new Template.Input(Template.Accepts.REQUIRED, true, true, 512, "Training data", null));
    }

    @Test
    void aTemplateWithoutAnInputTakesNone() {
        assertThat(parse(entry("")).get("smoke").input()).isEqualTo(Template.Input.NONE);
        assertThat(parse(entry("")).get("smoke").params()).isEmpty();
    }

    @Test
    void malformedParametersAndInputsAreRefused() {
        var cases = java.util.Map.ofEntries(
                java.util.Map.entry("params:\n  - name: Greeting\n    label: G\n    type: string\n", "must match"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n  - name: a\n    label: A\n    type: string\n",
                        "appears twice"),
                java.util.Map.entry("params:\n  - name: a\n    label: A\n    type: date\n", "type must be"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: integer\n    default: 9\n    max: 5\n",
                        "the default must be at most 5"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: integer\n    min: 5\n    max: 1\n",
                        "min is above max"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    max_length: 5000\n",
                        "max_length must be"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    min: 1\n", "only for integer"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: choice\n    choices: [only]\n", "at least 2"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    required: true\n    default: x\n",
                        "has no default"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    secret: true\n", "unknown field"),
                java.util.Map.entry("input:\n  accepts: sometimes\n", "accepts must be"),
                java.util.Map.entry("input:\n  accepts: optional\n  label: X\n", "an upload, a URL or both"),
                java.util.Map.entry(
                        "input:\n  accepts: optional\n  upload: true\n  max_size_mb: 4096\n  label: X\n",
                        "above the template's scratch_mb"),
                java.util.Map.entry("input:\n  accepts: optional\n  url: true\n", "label is required"));
        cases.forEach((block, message) ->
                assertThatThrownBy(() -> parse(withBlocks(block))).as(block).hasMessageContaining(message));
    }

    @Test
    void theDevelopmentCatalogHasEchoInputForTheBookingContract() throws Exception {
        try (var in = Files.newInputStream(Path.of("../templates/development.yaml"))) {
            var echo = TemplateCatalog.parse(in).get("echo-input");
            assertThat(echo.params()).extracting(Template.Param::name).containsExactly("greeting", "repeat");
            assertThat(echo.params().getFirst().required()).isTrue();
            assertThat(echo.params().get(1).defaultValue()).isEqualTo(1L);
            assertThat(echo.input().accepts()).isEqualTo(Template.Accepts.REQUIRED);
            assertThat(echo.input().upload()).isTrue();
            assertThat(echo.input().url()).isTrue();
        }
        try (var in = Files.newInputStream(Path.of("../templates/catalog.yaml"))) {
            var smoke = TemplateCatalog.parse(in).get("cuda-smoke-test");
            assertThat(smoke.params()).isEmpty();
            assertThat(smoke.input().accepts()).isEqualTo(Template.Accepts.NONE);
            assertThat(smoke.description()).isNotBlank();
        }
    }

    @Test
    void duplicateIdsOrAnEmptyCatalogAreRefused() {
        assertThatThrownBy(() -> parse(entry("") + entry(""))).hasMessageContaining("twice");
        assertThatThrownBy(() -> parse("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTemplateMustNameAServiceTheCatalogHas() {
        assertThat(parse(entry("")).get("smoke").service()).isEqualTo("tests");
        assertThatThrownBy(() -> parse(entry("service=nowhere")))
                .hasMessageContaining("its service nowhere isn't in services");
        assertThatThrownBy(() -> parse(entry("service=<none>"))).hasMessageContaining("service is required");
        assertThatThrownBy(() -> parse("", entry(""))).hasMessageContaining("non-empty services list");
    }

    @Test
    void malformedServicesAreRefused() {
        var cases = java.util.Map.of(
                SERVICES.replace("icon: flask", "icon: rocket"),
                "icon must be one of",
                SERVICES.replace("    running_label: Testing\n", ""),
                "running_label is required",
                SERVICES.replace("icon: flask", "icon: flask\n    colour: blue"),
                "unknown field colour",
                SERVICES + SERVICES.replace("services:\n", ""),
                "appears twice",
                SERVICES.replace("id: tests", "id: Tests"),
                "lowercase");
        cases.forEach((services, message) -> assertThatThrownBy(() -> parse(services, entry("service=tests")))
                .as(services)
                .hasMessageContaining(message));
    }

    @Test
    void bothCatalogsGroupTheirTemplatesIntoServices() throws Exception {
        try (var in = Files.newInputStream(Path.of("../templates/catalog.yaml"))) {
            var catalog = TemplateCatalog.read(in);
            assertThat(catalog.services().keySet())
                    .containsExactly("fine-tune", "run-code", "generate-images", "transcribe", "diagnostics");
            assertThat(catalog.services().get("diagnostics").hidden()).isTrue();
            assertThat(catalog.services().get("fine-tune").runningLabel()).isEqualTo("Fine-tuning");
            assertThat(catalog.templates().get("cuda-smoke-test").service()).isEqualTo("diagnostics");
        }
        try (var in = Files.newInputStream(Path.of("../templates/development.yaml"))) {
            var catalog = TemplateCatalog.read(in);
            assertThat(catalog.services().keySet()).containsExactly("developer-tests");
            assertThat(catalog.services().get("developer-tests").name()).isEqualTo("Test jobs");
            assertThat(catalog.templates().values())
                    .allSatisfy(template -> assertThat(template.service()).isEqualTo("developer-tests"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theApiOffersExactlyTheIconsTheCatalogAccepts() throws Exception {
        // The contract (api/openapi.yaml, kept current by ApiContractTests) enumerates the icons clients may draw.
        try (var in = Files.newInputStream(Path.of("../api/openapi.yaml"))) {
            var api = (java.util.Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(in);
            var schemas = (java.util.Map<String, Object>)
                    ((java.util.Map<String, Object>) api.get("components")).get("schemas");
            var properties = (java.util.Map<String, Object>)
                    ((java.util.Map<String, Object>) schemas.get("ServiceResponse")).get("properties");
            var icon = (java.util.Map<String, Object>) properties.get("icon");
            assertThat((java.util.List<String>) icon.get("enum"))
                    .containsExactlyElementsOf(com.idleo.controlplane.sessions.Service.ICONS);
        }
    }
}
