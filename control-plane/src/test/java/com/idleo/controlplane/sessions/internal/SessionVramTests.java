package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.sessions.SessionRequests;
import com.idleo.controlplane.sessions.Template;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The GPU memory a session needs, from the size of the renter's model (ADR 0022). */
class SessionVramTests {

    private static final long GIB = 1L << 30;

    private static Template fineTune(Template.@Nullable VramFromInput rule) {
        return new Template(
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
                1024,
                Duration.ofMinutes(5),
                List.of(),
                List.of(new Template.Input(
                        "model", "Your model", null, Template.InputKind.FOLDER, true, true, true, 40 * GIB)),
                List.of(),
                List.of(),
                null,
                rule,
                null);
    }

    private static final Template SIZED = fineTune(new Template.VramFromInput("model", 2048, 700));

    private static List<SessionRequests.Input> model(long bytes) {
        return List.of(new SessionRequests.Input("model", UUID.randomUUID(), null, bytes));
    }

    @Test
    void aModelNeedsItsBasePlusSomeForEachGibRoundedUpToAGpuClass() {
        // 2,048 + 700 x 14.2 = 11,988 MB: a 12 GB GPU.
        assertThat(SessionVram.needed(SIZED, model((long) (14.2 * GIB)))).isEqualTo(12_000);
        // 2,048 + 700 x 8 = 7,648: an 8 GB GPU.
        assertThat(SessionVram.needed(SIZED, model(8 * GIB))).isEqualTo(8_000);
        // 2,048 + 700 x 12 = 10,448: a 12 GB GPU.
        assertThat(SessionVram.needed(SIZED, model(12 * GIB))).isEqualTo(12_000);
        // 2,048 + 700 x 30 = 23,048: a 24 GB GPU.
        assertThat(SessionVram.needed(SIZED, model(30 * GIB))).isEqualTo(24_000);
    }

    @Test
    void neverBelowTheTemplatesMinimumAndAboveEveryClassItStaysAsWorkedOut() {
        assertThat(SessionVram.needed(SIZED, model(1_000)))
                .as("6,144 at least, as a class")
                .isEqualTo(8_000);
        assertThat(SessionVram.needed(SIZED, model(40 * GIB))).isEqualTo(2_048 + 28_000);
    }

    @Test
    void aLinksSizeIsntKnownSoItNeedsTheTemplatesMinimum() {
        assertThat(SessionVram.needed(SIZED, List.of(new SessionRequests.Input("model", null, "https://x.example", 0))))
                .isEqualTo(6_144);
        assertThat(SessionVram.needed(SIZED, List.of()))
                .as("an upload still going")
                .isEqualTo(6_144);
        assertThat(SessionVram.needed(fineTune(null), model(30 * GIB)))
                .as("no rule")
                .isEqualTo(6_144);
    }

    @Test
    void partGibsCountInProportionRoundedUp() {
        var rule = new Template.VramFromInput("model", 100, 1_000);
        assertThat(SessionVram.fromSize(rule, GIB / 2)).isEqualTo(600);
        assertThat(SessionVram.fromSize(rule, 1)).isEqualTo(101);
    }
}
