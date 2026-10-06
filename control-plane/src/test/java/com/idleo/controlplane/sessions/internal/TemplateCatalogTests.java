package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        fields.put("name", "Smoke");
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

    private static java.util.Map<String, com.idleo.controlplane.sessions.Template> parse(String entries) {
        return TemplateCatalog.parse(
                new ByteArrayInputStream(("templates:\n" + entries).getBytes(StandardCharsets.UTF_8)));
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
            "max_runtime_cap=<none>"
        }) {
            assertThatThrownBy(() -> parse(entry(overrides))).as(overrides).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void duplicateIdsOrAnEmptyCatalogAreRefused() {
        assertThatThrownBy(() -> parse(entry("") + entry(""))).hasMessageContaining("twice");
        assertThatThrownBy(() -> parse("")).isInstanceOf(IllegalStateException.class);
    }
}
