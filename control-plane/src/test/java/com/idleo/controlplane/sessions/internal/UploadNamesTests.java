package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ApiProblem;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Upload names and page cursors (ADR 0019). */
class UploadNamesTests {

    @Test
    void theStoredNameKeepsOnlySafeCharacters() {
        assertThat(UploadNames.stored("train.jsonl")).isEqualTo("train.jsonl");
        assertThat(UploadNames.stored("my data (v2).csv")).isEqualTo("my_data__v2_.csv");
        assertThat(UploadNames.stored("résumé.pdf")).isEqualTo("r_sum_.pdf");
        assertThat(UploadNames.stored("日本.txt")).isEqualTo("__.txt");
        assertThat(UploadNames.stored("...")).isEqualTo("...");
        assertThat(UploadNames.stored(".env")).isEqualTo(".env");
    }

    @Test
    void unsafeFilenamesAreRefused() {
        assertThat(UploadNames.problem("data.csv")).isEmpty();
        assertThat(UploadNames.problem("ok name, with spaces.txt")).isEmpty();
        for (var bad : new String[] {"", ".", "..", "a/b", "a\\b", "a\u0000b", "tab\there", "é".repeat(128)}) {
            assertThat(UploadNames.problem(bad)).as(bad).isPresent();
        }
        assertThat(UploadNames.problem("é".repeat(127))).as("254 bytes").isEmpty();
    }

    @Test
    void cursorsRoundTripAndForgeriesAreRefused() {
        var id = UUID.fromString("01a11300-0000-7000-8000-000000000001");
        assertThat(Cursors.id(Cursors.ofId(id))).isEqualTo(id);
        assertThat(Cursors.chunk(Cursors.ofChunk(2, 17))).containsExactly(2, 17);
        for (var bad : new String[] {"not base64!", Cursors.ofChunk(1, 3), "aTpub3QtYS11dWlk"}) {
            assertThatThrownBy(() -> Cursors.id(bad)).as(bad).isInstanceOf(ApiProblem.class);
        }
        assertThatThrownBy(() -> Cursors.chunk(Cursors.ofId(id))).isInstanceOf(ApiProblem.class);
        assertThat(Cursors.limit(20, 100)).isEqualTo(20);
        assertThatThrownBy(() -> Cursors.limit(0, 100)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> Cursors.limit(101, 100)).isInstanceOf(ApiProblem.class);
    }
}
