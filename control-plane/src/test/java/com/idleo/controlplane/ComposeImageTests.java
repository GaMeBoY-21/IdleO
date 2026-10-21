package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ComposeImageTests {

    @Test
    void localComposeUsesTheSamePinnedImagesAsTheTests() throws IOException {
        var compose = Files.readString(Path.of("compose.yaml"));

        // PostgreSQL is built from ops/postgres (WAL-G beside it, ADR 0036), on the tests' image.
        assertThat(compose).contains("build: ../ops/postgres");
        var postgres = Files.readString(Path.of("../ops/postgres/Dockerfile"));
        assertThat(postgres.lines().filter(line -> line.startsWith("FROM ")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("FROM " + TestcontainersConfiguration.POSTGRES_IMAGE));
        assertThat(compose).contains("image: " + TestcontainersConfiguration.VALKEY_IMAGE);
        assertThat(compose).contains("image: " + LocalSignInContainer.IMAGE);
        assertThat(compose).contains("image: " + TestStorageConfiguration.MINIO_IMAGE);
        // The mail catcher is development only, so no test starts it: its image is pinned by digest all the same.
        assertThat(compose).containsPattern("image: axllent/mailpit:v[0-9.]+@sha256:[0-9a-f]{64}");
        // So is the development image registry (ADR 0035).
        assertThat(compose).containsPattern("image: registry:2@sha256:[0-9a-f]{64}");
        // And the observability stack and its alert sender (ADR 0036).
        assertThat(compose).containsPattern("image: grafana/otel-lgtm:[0-9.]+@sha256:[0-9a-f]{64}");
        assertThat(compose).containsPattern("image: prom/alertmanager:v[0-9.]+@sha256:[0-9a-f]{64}");
    }
}
