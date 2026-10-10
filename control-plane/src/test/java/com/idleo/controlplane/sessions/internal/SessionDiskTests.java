package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.sessions.Template;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The disk a session needs on its host: its uploads, its scratch disk, and a 10% margin (ADR 0022). */
class SessionDiskTests {

    private static final Template FOUR_GB_SCRATCH = new Template(
            "fine-tune",
            "fine-tune",
            "Fine-tune",
            "Trains your model.",
            "registry.example/tune@sha256:" + "a".repeat(64),
            List.of(),
            6_144,
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            null,
            2048,
            2000,
            256,
            4_096,
            Duration.ofMinutes(5),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null);

    @Test
    void theInputsAndTheScratchDiskWithAMargin() {
        assertThat(SessionDisk.neededMb(FOUR_GB_SCRATCH, 0))
                .as("4,096 x 1.1, rounded up")
                .isEqualTo(4_506);
        // A 14 GiB model and a 1 MiB dataset: 14,336 + 1 + 4,096 = 18,433 MB, x 1.1.
        assertThat(SessionDisk.neededMb(FOUR_GB_SCRATCH, (14L << 30) + (1L << 20)))
                .isEqualTo(20_277);
        assertThat(SessionDisk.neededMb(FOUR_GB_SCRATCH, 1))
                .as("a part MiB counts whole")
                .isEqualTo(4_507);
    }
}
