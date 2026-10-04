package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ComposeImageTests {

    @Test
    void localComposeUsesTheSamePinnedImageAsTheTests() throws IOException {
        var compose = Files.readString(Path.of("compose.yaml"));

        assertThat(compose).contains("image: " + TestcontainersConfiguration.POSTGRES_IMAGE);
    }
}
