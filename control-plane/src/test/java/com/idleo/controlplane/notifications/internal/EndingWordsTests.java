package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** An email says why a session ended in the words the web app uses: both read api/endings.json (ADR 0026). */
class EndingWordsTests {

    private final EndingWords words = new EndingWords(JsonMapper.builder().build());

    @Test
    void knownCodesHaveASentenceAndANextStep() {
        var credit = words.of("credit_exhausted");
        assertThat(credit.sentence()).isEqualTo("Your credit ran out, so it saved a checkpoint and stopped.");
        assertThat(credit.next()).startsWith("Once you have more credit");
        assertThat(words.of("oom_killed").sentence()).isEqualTo("The job ran out of memory.");
    }

    @Test
    void anExitCodeIsRead() {
        assertThat(words.of("exit_code_137").sentence()).isEqualTo("The job exited with code 137.");
        assertThat(words.of("exit_code_-1").sentence()).isEqualTo("The job exited with code -1.");
    }

    @Test
    void aCodeNobodyKnowsIsShownReadablyNotHidden() {
        var unknown = words.of("disk_on_fire");
        assertThat(unknown.sentence()).isEqualTo("It ended: disk on fire.");
        assertThat(unknown.next()).startsWith("Check its log");
    }
}
