package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.sessions.Template;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A renter's parameters against the catalog, and how they reach the job (ADR 0019). */
class SessionParamsTests {

    private static Template.Param param(
            String name, Template.ParamType type, boolean required, Object defaultValue, Object... limits) {
        return new Template.Param(
                name,
                name,
                null,
                type,
                required,
                defaultValue,
                limits.length > 0 ? (BigDecimal) limits[0] : null,
                limits.length > 1 ? (BigDecimal) limits[1] : null,
                limits.length > 2 ? (Integer) limits[2] : null,
                limits.length > 3 ? List.of(((String) limits[3]).split(",")) : List.of());
    }

    private static final Template TEMPLATE = new Template(
            "fine-tune",
            "fine-tune",
            "Fine-tune",
            "Trains a model.",
            "registry.example/tune@sha256:" + "a".repeat(64),
            List.of(),
            1024,
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            null,
            2048,
            2000,
            256,
            1024,
            Duration.ofMinutes(5),
            List.of(
                    param("dataset_name", Template.ParamType.STRING, true, null, null, null, 20),
                    param("epochs", Template.ParamType.INTEGER, false, 2L, BigDecimal.ONE, BigDecimal.TEN),
                    param("rate", Template.ParamType.NUMBER, false, null, BigDecimal.ZERO, BigDecimal.ONE),
                    param("verbose", Template.ParamType.BOOLEAN, false, false),
                    param("mode", Template.ParamType.CHOICE, false, null, null, null, null, "fast,careful"),
                    param("notes", Template.ParamType.STRING, false, null)),
            Template.Input.NONE);

    private static Map<String, Object> values(Object... pairs) {
        var values = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private static List<ApiProblem.FieldError> errors(Map<String, Object> values) {
        try {
            SessionParams.resolve(TEMPLATE, values);
        } catch (ApiProblem problem) {
            assertThat(problem.problem()).isEqualTo(Problem.VALIDATION_FAILED);
            @SuppressWarnings("unchecked")
            var errors = (List<ApiProblem.FieldError>) problem.properties().get("errors");
            return errors;
        }
        throw new AssertionError("accepted: " + values);
    }

    @Test
    void everyTypeIsCheckedAndKeptAsItsOwnKindOfValue() {
        var resolved = SessionParams.resolve(
                TEMPLATE,
                values(
                        "dataset_name",
                        "alpaca",
                        "epochs",
                        3,
                        "rate",
                        0.0002,
                        "verbose",
                        true,
                        "mode",
                        "fast",
                        "notes",
                        "line one\n\tline two"));

        assertThat(resolved)
                .containsExactly(
                        Map.entry("dataset_name", "alpaca"),
                        Map.entry("epochs", 3L),
                        Map.entry("rate", new BigDecimal("0.0002")),
                        Map.entry("verbose", true),
                        Map.entry("mode", "fast"),
                        Map.entry("notes", "line one\n\tline two"));
    }

    @Test
    void defaultsAreFilledInAndOptionalOnesWithoutADefaultAreLeftOut() {
        var resolved = SessionParams.resolve(TEMPLATE, values("dataset_name", "alpaca", "rate", null));

        assertThat(resolved)
                .containsExactly(
                        Map.entry("dataset_name", "alpaca"), Map.entry("epochs", 2L), Map.entry("verbose", false));
    }

    @Test
    void wrongValuesNameTheirFields() {
        assertThat(errors(values(
                        "epochs", 2.5,
                        "rate", "fast",
                        "verbose", "yes",
                        "mode", "slow")))
                .containsExactly(
                        new ApiProblem.FieldError("params.dataset_name", "is required."),
                        new ApiProblem.FieldError("params.epochs", "must be a whole number."),
                        new ApiProblem.FieldError("params.rate", "must be a number."),
                        new ApiProblem.FieldError("params.verbose", "must be true or false."),
                        new ApiProblem.FieldError("params.mode", "must be one of fast, careful."));
    }

    @Test
    void limitsAreEnforced() {
        assertThat(errors(values("dataset_name", "x".repeat(21), "epochs", 11, "rate", -0.5)))
                .containsExactly(
                        new ApiProblem.FieldError("params.dataset_name", "must be at most 20 characters."),
                        new ApiProblem.FieldError("params.epochs", "must be at most 10."),
                        new ApiProblem.FieldError("params.rate", "must be at least 0."));
        assertThat(errors(values("dataset_name", "a", "epochs", BigInteger.TWO.pow(70))))
                .extracting(ApiProblem.FieldError::field)
                .containsExactly("params.epochs");
        assertThat(errors(values("dataset_name", "a", "notes", "x".repeat(SessionParams.DEFAULT_MAX_LENGTH + 1))))
                .containsExactly(new ApiProblem.FieldError("params.notes", "must be at most 1000 characters."));
    }

    @Test
    void controlCharactersOtherThanTabAndNewlineAreRefused() {
        for (var bad : List.of("a\u0000b", "bell\u0007", "back\rspace", "esc\u001b[31m", "del\u007f", "c1\u0085")) {
            assertThat(errors(values("dataset_name", bad)))
                    .as(bad)
                    .containsExactly(new ApiProblem.FieldError(
                            "params.dataset_name", "can't contain control characters other than tab and newline."));
        }
    }

    @Test
    void anUnknownNameIsRefusedNeverIgnored() {
        assertThat(errors(values("dataset_name", "a", "epoch", 2)))
                .containsExactly(new ApiProblem.FieldError("params.epoch", "isn't a parameter of template fine-tune."));
    }

    @Test
    void valuesBecomeEnvironmentVariablesAndNothingElse() {
        var resolved = SessionParams.resolve(
                TEMPLATE,
                values("dataset_name", "alpaca", "rate", new BigDecimal("1E-4"), "verbose", true, "mode", "careful"));

        assertThat(SessionParams.env(resolved, "train.jsonl", null))
                .containsExactly(
                        Map.entry("SESSION_PARAM_DATASET_NAME", "alpaca"),
                        Map.entry("SESSION_PARAM_EPOCHS", "2"),
                        Map.entry("SESSION_PARAM_RATE", "0.0001"),
                        Map.entry("SESSION_PARAM_VERBOSE", "true"),
                        Map.entry("SESSION_PARAM_MODE", "careful"),
                        Map.entry("SESSION_INPUT_FILE", "/work/input/train.jsonl"));
        assertThat(SessionParams.env(Map.of(), null, "https://example.com/data.jsonl"))
                .containsExactly(Map.entry("SESSION_INPUT_URL", "https://example.com/data.jsonl"));
    }

    @Test
    void theEnvironmentStaysUnder32KiB() {
        var big = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < 9; i++) {
            big.put("text_" + i, "x".repeat(4_000));
        }
        assertThatThrownBy(() -> SessionParams.env(big, null, null))
                .isInstanceOf(ApiProblem.class)
                .hasMessageContaining("invalid fields");
        big.remove("text_8");
        assertThat(SessionParams.env(big, null, null)).hasSize(8);
    }
}
