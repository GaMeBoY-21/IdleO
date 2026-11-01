package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Starts its own context (the extra property keeps it out of the shared one), so the whole startup
 * log is captured.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "test.context=logging")
@Import({
    TestcontainersConfiguration.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestStorageConfiguration.class
})
@DirtiesContext
class LoggingTests {

    @Test
    void logsAreStructuredEcsJson(CapturedOutput output) {
        assertThat(output.getOut())
                .contains("\"ecs\":{\"version\"")
                .contains("\"service\":{\"name\":\"control-plane\"");
    }

    @Test
    void startupLogsNoSecrets(CapturedOutput output) {
        assertThat(output.getAll())
                .doesNotContain(TestcontainersConfiguration.DATABASE_PASSWORD)
                .doesNotContain(TestStorageConfiguration.ROOT_PASSWORD)
                .doesNotContainIgnoringCase("generated security password");
    }
}
