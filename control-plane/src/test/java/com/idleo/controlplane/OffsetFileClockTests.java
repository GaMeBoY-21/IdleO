package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The end-to-end suite's clock (ADR 0057): the real time until its file says otherwise, and never a crash for a bad file. */
class OffsetFileClockTests {

    @TempDir
    Path folder;

    private static void aboutNow(Instant shown, Duration ahead) {
        var expected = Instant.now().plus(ahead);
        assertThat(Duration.between(shown, expected).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void itsTheRealTimeWithNoFileAndMovesWhenTheFileSaysSo() throws Exception {
        var file = folder.resolve("offset");
        var clock = new OffsetFileClock(file);
        aboutNow(clock.instant(), Duration.ZERO);

        Files.writeString(file, "2678400\n");
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> aboutNow(clock.instant(), Duration.ofDays(31)));

        // A half-written file keeps what it last read.
        Files.writeString(file, "not a number");
        Thread.sleep(300);
        aboutNow(clock.instant(), Duration.ofDays(31));
    }

    @Test
    void itStartsFromAnOffsetAlreadyInTheFile() throws Exception {
        var file = folder.resolve("offset");
        Files.writeString(file, "3600");
        aboutNow(new OffsetFileClock(file).instant(), Duration.ofHours(1));
    }
}
