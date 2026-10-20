package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.sessions.Template;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
                    group: basic
                    required: true
                    max_length: 200
                  - name: repeat
                    label: Repeat
                    help: How many times.
                    type: integer
                    group: advanced
                    default: 1
                    min: 1
                    max: 5
                  - name: rate
                    label: Learning rate
                    type: number
                    group: advanced
                    default: 0.0002
                  - name: mode
                    label: Mode
                    type: choice
                    group: advanced
                    choices: [fast, careful]
                    default: careful
                inputs:
                  - name: model
                    label: Your model
                    kind: folder
                    accepts: [upload, link]
                    required: true
                    max_size_gb: 40
                  - name: data
                    label: Training data
                    help: One example a line.
                    kind: file
                    accepts: [link]
                questions:
                  - preset: thoroughness
                    label: How thorough?
                    options:
                      - { id: quick, label: Quick, minutes: 15, sets: { repeat: 1, mode: fast } }
                      - { id: thorough, label: Thorough, sets: { repeat: 5 } }
                    default: thorough
                  - param: greeting
                  - gpu_memory: true
                    label: GPU memory you need
                    choices_gb: [8, 12]
                    default_gb: 8
                budget_chips: [5000, 10000]
                default_budget: 10000
                vram_from_input: { input: model, base_mb: 2048, per_gb_mb: 700 }
                you_get: adapter_model.safetensors
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
    void parametersInputsAndQuestionsLoadWithTheirDefaultsChecked() {
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
        assertThat(template.params().get(0).group()).isEqualTo(Template.Group.BASIC);
        assertThat(template.inputs())
                .containsExactly(
                        new Template.Input(
                                "model",
                                "Your model",
                                null,
                                Template.InputKind.FOLDER,
                                true,
                                true,
                                true,
                                40L << 30,
                                List.of()),
                        new Template.Input(
                                "data",
                                "Training data",
                                "One example a line.",
                                Template.InputKind.FILE,
                                false,
                                true,
                                false,
                                null,
                                List.of()));
        var preset = template.preset("thoroughness").orElseThrow();
        assertThat(preset.defaultOption()).isEqualTo("thorough");
        assertThat(preset.option("quick").orElseThrow().sets())
                .isEqualTo(java.util.Map.of("repeat", 1L, "mode", "fast"));
        assertThat(preset.option("quick").orElseThrow().minutes()).isEqualTo(15);
        assertThat(preset.option("thorough").orElseThrow().minutes()).isNull();
        assertThat(template.questions().get(1)).isEqualTo(new Template.Question.Basic("greeting"));
        assertThat(template.questions().get(2))
                .isEqualTo(new Template.Question.GpuMemory("GPU memory you need", null, java.util.List.of(8, 12), 8));
        assertThat(template.budgetChips()).containsExactly(5000L, 10000L);
        assertThat(template.defaultBudget()).isEqualTo(10000L);
        assertThat(template.vramFromInput()).isEqualTo(new Template.VramFromInput("model", 2048, 700));
        assertThat(template.youGet()).isEqualTo("adapter_model.safetensors");
    }

    @Test
    void aTemplateWithoutInputsOrQuestionsTakesNone() {
        var template = parse(entry("")).get("smoke");
        assertThat(template.inputs()).isEmpty();
        assertThat(template.params()).isEmpty();
        assertThat(template.questions()).isEmpty();
        assertThat(template.budgetChips()).isEmpty();
        assertThat(template.defaultBudget()).isNull();
        assertThat(template.youGet()).isNull();
    }

    /** A block of YAML with one line replaced, for the cases below. */
    private static String echoWith(String line, String replacement) {
        if (!ECHO_PARAMS.contains(line)) {
            throw new AssertionError("not in the block: " + line);
        }
        return ECHO_PARAMS.replace(line, replacement);
    }

    @Test
    void malformedInputsQuestionsAndBudgetsAreRefused() {
        var cases = java.util.Map.ofEntries(
                java.util.Map.entry(echoWith("kind: folder", "kind: archive"), "kind must be folder or file"),
                java.util.Map.entry(echoWith("accepts: [link]", "accepts: [url]"), "accepts must list"),
                java.util.Map.entry(echoWith("accepts: [link]", "accepts: []"), "accepts must list"),
                java.util.Map.entry(echoWith("max_size_gb: 40", "max_size_gb: 0"), "max_size_gb above 0"),
                java.util.Map.entry(
                        echoWith("accepts: [link]", "accepts: [link]\n        max_size_gb: 1"),
                        "only for an input that takes an upload"),
                java.util.Map.entry(echoWith("- name: data", "- name: model"), "input model appears twice"),
                java.util.Map.entry(
                        echoWith(
                                "max_size_gb: 40",
                                "max_size_gb: 40\n        accepts_extensions: [.jsonl]\n        not_accepted: no"),
                        "accepts_extensions is for a file that takes an upload"),
                java.util.Map.entry(
                        echoWith(
                                "accepts: [link]",
                                "accepts: [upload]\n        max_size_gb: 1\n        must_contain:\n          - any_of: [a]\n            message: x"),
                        "must_contain is for a folder that takes an upload"),
                java.util.Map.entry(
                        echoWith(
                                "max_size_gb: 40",
                                "max_size_gb: 40\n        must_contain:\n          - any_of: []\n            message: x"),
                        "any_of lists file names"),
                java.util.Map.entry(
                        echoWith(
                                "max_size_gb: 40",
                                "max_size_gb: 40\n        must_contain:\n          - any_of: [config.json]"),
                        "message"),
                java.util.Map.entry(
                        echoWith(
                                "max_size_gb: 40",
                                "max_size_gb: 40\n        must_contain:\n          - any_of: [config.json]\n            message: x\n            max_depth: 0"),
                        "max_depth must be"),
                java.util.Map.entry(
                        echoWith(
                                "accepts: [link]",
                                "accepts: [upload]\n        max_size_gb: 1\n        accepts_extensions: [jsonl]\n        not_accepted: x"),
                        "extensions such as .jsonl"),
                java.util.Map.entry(
                        echoWith("accepts: [link]", "accepts: [link]\n        not_accepted: x"),
                        "not_accepted goes with accepts_extensions"),
                java.util.Map.entry(echoWith("sets: { repeat: 5 }", "sets: { epochs: 5 }"), "sets epochs, which isn't"),
                java.util.Map.entry(
                        echoWith("sets: { repeat: 5 }", "sets: { greeting: hi }"), "advanced parameters only"),
                java.util.Map.entry(echoWith("sets: { repeat: 5 }", "sets: { repeat: 9 }"), "repeat must be at most 5"),
                java.util.Map.entry(echoWith("default: thorough", "default: slow"), "one of its options"),
                java.util.Map.entry(echoWith("      - param: greeting\n", ""), "must be asked as a question"),
                java.util.Map.entry(echoWith("- param: greeting", "- param: repeat"), "isn't a basic parameter"),
                java.util.Map.entry(echoWith("choices_gb: [8, 12]", "choices_gb: [8, 10]"), "choices_gb must be"),
                java.util.Map.entry(echoWith("default_gb: 8", "default_gb: 16"), "default_gb must be one of"),
                java.util.Map.entry(echoWith("budget_chips: [5000, 10000]", "budget_chips: [10000, 5000]"), "rising"),
                java.util.Map.entry(echoWith("default_budget: 10000", "default_budget: 7000"), "one of budget_chips"),
                java.util.Map.entry(echoWith("input: model, base_mb", "input: data, base_mb"), "takes an upload"),
                java.util.Map.entry(echoWith("input: model, base_mb", "input: weights, base_mb"), "takes an upload"),
                java.util.Map.entry(echoWith("per_gb_mb: 700", "per_gb_mb: 0"), "per_gb_mb must be"),
                java.util.Map.entry(echoWith("        default: 1\n", "        required: true\n"), "never required"));
        cases.forEach((block, message) ->
                assertThatThrownBy(() -> parse(withBlocks(block))).as(block).hasMessageContaining(message));
    }

    @Test
    void anInputsContentRulesAreRead() {
        var block = withBlocks(ECHO_PARAMS)
                .replace(
                        "max_size_gb: 40",
                        "max_size_gb: 40\n        must_contain:\n          - any_of: [config.json]\n            max_depth: 2\n            message: No config.\n          - any_of: [\"*.safetensors\", \"*.bin\"]\n            message: No weights.")
                .replace(
                        "accepts: [link]",
                        "accepts: [upload]\n        max_size_gb: 1\n        accepts_extensions: [.jsonl, .json]\n        not_accepted: Not JSONL.");
        var template = parse(block).get("smoke");

        assertThat(template.input("model").orElseThrow().mustContain())
                .containsExactly(
                        new Template.ContentRule(List.of("config.json"), 2, "No config."),
                        new Template.ContentRule(List.of("*.safetensors", "*.bin"), null, "No weights."));
        assertThat(template.input("data").orElseThrow().mustContain())
                .containsExactly(new Template.ContentRule(List.of("*.jsonl", "*.json"), null, "Not JSONL."));
    }

    @Test
    void aRuleLooksForFileNamesByDepthAndSuffixInAnyCase() {
        var config = new Template.ContentRule(List.of("config.json"), 2, "x");
        assertThat(config.matches("config.json")).isTrue();
        assertThat(config.matches("llm/Config.JSON")).isTrue();
        assertThat(config.matches("a/b/config.json")).as("too deep").isFalse();
        assertThat(config.matches("my-config.json")).isFalse();
        var weights = new Template.ContentRule(List.of("*.safetensors", "*.bin"), null, "x");
        assertThat(weights.matches("a/b/c/model-0001.SafeTensors")).isTrue();
        assertThat(weights.matches("pytorch_model.bin")).isTrue();
        assertThat(weights.matches("notes.binary")).isFalse();
        assertThat(weights.matches("weights.safetensors.txt")).isFalse();
    }

    @Test
    void aGpuMemoryChoiceBelowTheTemplatesMinimumIsRefused() {
        assertThatThrownBy(() -> parse(withBlocks(ECHO_PARAMS).replace("min_vram_mb: 1024", "min_vram_mb: 10000")))
                .hasMessageContaining("8 GB is below the template's min_vram_mb");
    }

    @Test
    void malformedParametersAreRefused() {
        var cases = java.util.Map.ofEntries(
                java.util.Map.entry(
                        "params:\n  - name: Greeting\n    label: G\n    type: string\n    group: advanced\n",
                        "must match"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    group: advanced\n  - name: a\n    label: A\n    type: string\n    group: advanced\n",
                        "appears twice"),
                java.util.Map.entry("params:\n  - name: a\n    label: A\n    type: date\n", "type must be"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: integer\n    group: advanced\n    default: 9\n    max: 5\n",
                        "the default must be at most 5"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: integer\n    group: advanced\n    min: 5\n    max: 1\n",
                        "min is above max"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    group: advanced\n    max_length: 5000\n",
                        "max_length must be"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    group: advanced\n    min: 1\n",
                        "only for integer"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: choice\n    group: advanced\n    choices: [only]\n",
                        "at least 2"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    group: advanced\n    required: true\n    default: x\n",
                        "has no default"),
                java.util.Map.entry(
                        "params:\n  - name: a\n    label: A\n    type: string\n    group: advanced\n    secret: true\n",
                        "unknown field"),
                java.util.Map.entry("params:\n  - name: a\n    label: A\n    type: string\n", "group is required"),
                java.util.Map.entry("input:\n  accepts: none\n", "unknown field input"));
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
            var file = echo.input("file").orElseThrow();
            assertThat(file.required()).isTrue();
            assertThat(file.kind()).isEqualTo(Template.InputKind.FILE);
            assertThat(file.upload()).isTrue();
            assertThat(file.link()).isTrue();
            assertThat(file.maxSizeBytes()).isEqualTo(512L << 20);
        }
        try (var in = Files.newInputStream(Path.of("../templates/catalog.yaml"))) {
            var smoke = TemplateCatalog.parse(in).get("cuda-smoke-test");
            assertThat(smoke.params()).isEmpty();
            assertThat(smoke.inputs()).isEmpty();
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
            assertThat(catalog.services().keySet()).containsExactly("fine-tune", "run-code", "diagnostics");
            assertThat(catalog.services().get("diagnostics").hidden()).isTrue();
            assertThat(catalog.services().get("fine-tune").runningLabel()).isEqualTo("Fine-tuning");
            assertThat(catalog.templates().get("cuda-smoke-test").service()).isEqualTo("diagnostics");
        }
        try (var in = Files.newInputStream(Path.of("../templates/development.yaml"))) {
            var catalog = TemplateCatalog.read(in);
            assertThat(catalog.services().keySet()).containsExactly("fine-tune", "run-code", "developer-tests");
            assertThat(catalog.services().get("developer-tests").name()).isEqualTo("Test jobs");
            assertThat(catalog.templates().get("echo-input").service()).isEqualTo("developer-tests");
        }
    }

    /**
     * The local stack shows the production services in their real words: every service the development catalog
     * shares with catalog.yaml has the same id, name, tagline, running label and icon, both offered ones are
     * there, and each runs one template the development runner simulates as that service.
     */
    @Test
    void theDevelopmentCatalogShowsTheProductionServicesWordForWord() throws Exception {
        TemplateCatalog.Parsed production;
        TemplateCatalog.Parsed development;
        try (var in = Files.newInputStream(Path.of("../templates/catalog.yaml"))) {
            production = TemplateCatalog.read(in);
        }
        try (var in = Files.newInputStream(Path.of("../templates/development.yaml"))) {
            development = TemplateCatalog.read(in);
        }
        var offered = production.services().values().stream()
                .filter(service -> !service.hidden())
                .toList();
        assertThat(offered).hasSize(2);
        for (var service : offered) {
            assertThat(development.services().get(service.id()))
                    .as("development.yaml's %s", service.id())
                    .isEqualTo(service);
            assertThat(development.templates().values())
                    .filteredOn(template -> template.service().equals(service.id()))
                    .singleElement()
                    .satisfies(template -> {
                        assertThat(template.args()).containsSubsequence("--simulate", service.id());
                        assertThat(template.name()).endsWith("(simulated)");
                        assertThat(template.description()).contains("development runner");
                        assertThat(template.inputs()).anyMatch(Template.Input::required);
                    });
        }
        development.services().values().stream()
                .filter(service -> production.services().containsKey(service.id()))
                .forEach(service ->
                        assertThat(service).isEqualTo(production.services().get(service.id())));
    }

    /**
     * The real runner's local catalog (ADR 0035) shows the same two services in the same words, and its two templates take
     * what the simulated ones take, so the booking screen, the CLI and the SDK are the same on either stack.
     */
    @Test
    void theRealRunnerCatalogHasTheProductionServicesAndTheSimulatedTemplatesShapes() throws Exception {
        TemplateCatalog.Parsed production;
        TemplateCatalog.Parsed real;
        TemplateCatalog.Parsed simulated;
        try (var in = Files.newInputStream(Path.of("../templates/catalog.yaml"))) {
            production = TemplateCatalog.read(in);
        }
        try (var in = Files.newInputStream(Path.of("../templates/development-real.yaml"))) {
            real = TemplateCatalog.read(in);
        }
        try (var in = Files.newInputStream(Path.of("../templates/development.yaml"))) {
            simulated = TemplateCatalog.read(in);
        }
        // The production services, and the hidden one the hostile test runs under (development only, never in
        // catalog.yaml).
        assertThat(real.services().values().stream()
                        .filter(service -> !service.hidden())
                        .toList())
                .containsExactlyInAnyOrderElementsOf(production.services().values().stream()
                        .filter(service -> !service.hidden())
                        .toList());
        assertThat(real.services().get("developer-tests").hidden()).isTrue();
        assertThat(real.templates()).containsOnlyKeys("fine-tune-qlora", "run-your-code", "hostile-test");
        var pairs = java.util.Map.of("fine-tune-qlora", "fine-tune-simulated", "run-your-code", "run-code-simulated");
        pairs.forEach((realId, simulatedId) -> {
            var a = real.templates().get(realId);
            var b = simulated.templates().get(simulatedId);
            assertThat(a.service()).isEqualTo(b.service());
            assertThat(a.inputs()).isEqualTo(b.inputs());
            assertThat(a.questions()).isEqualTo(b.questions());
            assertThat(a.params()).isEqualTo(b.params());
            assertThat(a.budgetChips()).isEqualTo(b.budgetChips());
            assertThat(a.vramFromInput()).isEqualTo(b.vramFromInput());
            assertThat(a.args()).doesNotContain("--simulate");
            assertThat(a.image()).startsWith("registry.idleo.test:5000/idleo/pytorch@sha256:");
            // The sandbox's limits are the technical design's: memory, CPUs and processes for the smallest GPU class.
            assertThat(a.memoryMb()).isEqualTo(12288);
            assertThat(a.pidsLimit()).isEqualTo(512);
        });
    }

    /** The hostile test's Attack parameter offers exactly the attacks hostile.py has: the 14 of ADR 0035, in its order. */
    @Test
    void theHostileTestTemplateOffersExactlyTheAttacksTheHostileJobHas() throws Exception {
        TemplateCatalog.Parsed real;
        try (var in = Files.newInputStream(Path.of("../templates/development-real.yaml"))) {
            real = TemplateCatalog.read(in);
        }
        var hostile = real.templates().get("hostile-test");
        assertThat(hostile.service()).isEqualTo("developer-tests");
        assertThat(hostile.image()).startsWith("registry.idleo.test:5000/idleo/hostile@sha256:");
        var script = Files.readString(Path.of("../agent/runner/tests/hostile/hostile.py"));
        var attacks = java.util.regex.Pattern.compile("ATTACKS = \\{f\\.__name__: f for f in \\(([^)]*)\\)\\}")
                .matcher(script);
        assertThat(attacks.find()).isTrue();
        assertThat(hostile.params().get(0).choices())
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(attacks.group(1).split(","))
                                .map(String::trim)
                                .toList());
        assertThat(hostile.params().get(0).choices()).hasSize(14);
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
