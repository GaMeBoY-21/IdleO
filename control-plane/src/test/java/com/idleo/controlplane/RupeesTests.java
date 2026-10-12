package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Rupees.format writes money as the web app's formatPaise does: both read the cases in api/money-format.json. */
class RupeesTests {

    @Test
    void writesEveryCaseInTheSharedFile() throws Exception {
        var cases = JsonMapper.builder()
                .build()
                .readTree(Files.readString(Path.of("../api/money-format.json")))
                .get("cases");
        assertThat(cases.size()).isGreaterThan(10);
        for (var one : cases) {
            assertThat(Rupees.format(one.get("paise").asLong()))
                    .as("%d paise", one.get("paise").asLong())
                    .isEqualTo(one.get("text").asString());
        }
    }

    @Test
    void takesTheLargestAndSmallestAmountsExactly() {
        assertThat(Rupees.format(Long.MAX_VALUE)).isEqualTo("₹92,23,37,20,36,85,47,758.07");
        assertThat(Rupees.format(Long.MIN_VALUE)).isEqualTo("−₹92,23,37,20,36,85,47,758.08");
    }
}
