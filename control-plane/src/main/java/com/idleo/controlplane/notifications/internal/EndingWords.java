package com.idleo.controlplane.notifications.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Why a session ended, in plain words and what to do next. The sentences are api/endings.json, the one file the
 * web app reads too, so an email says what the screen says (ADR 0026).
 */
@Component
class EndingWords {

    /** A sentence and what to do next, both ending in a full stop. */
    record Words(String sentence, String next) {}

    private static final Pattern EXIT_CODE = Pattern.compile("^exit_code_(-?\\d+)$");

    private final Map<String, Words> codes = new HashMap<>();
    private final Words exit;
    private final String unknownNext;

    EndingWords(JsonMapper json) {
        JsonNode root;
        try (var in = new ClassPathResource("api/endings.json").getInputStream()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("api/endings.json can't be read", e);
        }
        root.get("codes").properties().forEach(entry -> codes.put(entry.getKey(), words(entry.getValue())));
        exit = words(root.get("exit"));
        unknownNext = root.get("unknown").get("next").asString();
    }

    Words of(String code) {
        var exitCode = EXIT_CODE.matcher(code);
        if (exitCode.matches()) {
            return new Words(exit.sentence().replace("{code}", exitCode.group(1)), exit.next());
        }
        var known = codes.get(code);
        if (known != null) {
            return known;
        }
        return new Words("It ended: " + code.toLowerCase(Locale.ROOT).replace('_', ' ') + ".", unknownNext);
    }

    private static Words words(JsonNode node) {
        return new Words(node.get("sentence").asString(), node.get("next").asString());
    }
}
