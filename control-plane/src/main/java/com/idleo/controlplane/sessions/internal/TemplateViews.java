package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.CreateSessionRequest;
import com.idleo.controlplane.sessions.Template;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** How GET /v1/templates describes what a template takes (ADR 0019, ADR 0022): its parameters, inputs and questions. */
final class TemplateViews {

    private TemplateViews() {}

    /**
     * A parameter, which reaches the job as {@code SESSION_PARAM_<NAME>}. Never put secrets in one.
     *
     * @param group basic: asked as a plain question (see the template's questions); advanced: behind the one
     *     "Advanced" row, with its default set
     * @param defaultValue what it is when the renter gives none; null when an optional one is simply left out
     * @param maxLength for a string, the most characters it takes
     */
    record TemplateParam(
            String name,
            String label,
            @Nullable String help,

            @Schema(allowableValues = {"string", "integer", "number", "boolean", "choice"})
            String type,

            @Schema(allowableValues = {"basic", "advanced"}) String group,

            boolean required,

            @Nullable @Schema(types = {"string", "integer", "number", "boolean"})
            Object defaultValue,

            @Nullable BigDecimal min,
            @Nullable BigDecimal max,
            @Nullable Integer maxLength,
            List<String> choices) {

        static TemplateParam of(Template.Param param) {
            return new TemplateParam(
                    param.name(),
                    param.label(),
                    param.help(),
                    lower(param.type()),
                    lower(param.group()),
                    param.required(),
                    param.defaultValue(),
                    param.min(),
                    param.max(),
                    param.type() == Template.ParamType.STRING
                            ? (param.maxLength() == null ? SessionParams.DEFAULT_MAX_LENGTH : param.maxLength())
                            : null,
                    param.choices());
        }
    }

    /**
     * Something the renter brings, by name: an upload ({@code POST /v1/uploads}), staged in {@code
     * /work/input/<name>/}, or an https link the job fetches itself inside its sandbox.
     *
     * @param kind a folder of files (a model, a project), or exactly one file
     * @param maxSizeBytes the largest upload it takes; null when it takes only a link
     * @param mustContain what an upload's files must show, each rule with the words the renter reads when it isn't so; the
     *     server checks them when the session is booked or quoted ({@code input-not-usable}), and the booking screen
     *     applies the same rules as soon as the files are chosen. A link is never checked at booking
     */
    record TemplateInput(
            String name,
            String label,
            @Nullable String help,

            @Schema(allowableValues = {"folder", "file"}) String kind,

            boolean upload,
            boolean link,
            boolean required,
            @Nullable Long maxSizeBytes,
            List<ContentRuleView> mustContain) {

        static TemplateInput of(Template.Input input) {
            return new TemplateInput(
                    input.name(),
                    input.label(),
                    input.help(),
                    lower(input.kind()),
                    input.upload(),
                    input.link(),
                    input.required(),
                    input.maxSizeBytes(),
                    input.mustContain().stream().map(ContentRuleView::of).toList());
        }
    }

    /**
     * Some file an upload must hold.
     *
     * @param anyOf the file names it looks for: an exact name ({@code config.json}) or {@code *.suffix}, in any case
     * @param maxDepth at most this many path segments deep ({@code config.json} at the top, or under one folder, is 2);
     *     null for anywhere
     * @param message the renter's words when no file matches
     */
    record ContentRuleView(List<String> anyOf, @Nullable Integer maxDepth, String message) {

        static ContentRuleView of(Template.ContentRule rule) {
            return new ContentRuleView(rule.anyOf(), rule.maxDepth(), rule.message());
        }
    }

    /**
     * One option of a preset question.
     *
     * @param minutes about how long it typically runs; null if there's no usual
     * @param sets the advanced parameters it sets, by name
     */
    record PresetOption(
            String id,
            String label,
            @Nullable Integer minutes,

            @Schema(additionalPropertiesSchema = CreateSessionRequest.ParamValue.class)
            Map<String, Object> sets) {}

    /**
     * A plain question the booking screen asks, after the inputs, in this order. One of three kinds:
     *
     * <ul>
     *   <li>{@code preset}: a choice, named {@code name}, whose options each set several advanced parameters; the
     *       booking answers it in {@code presets};
     *   <li>{@code param}: the basic parameter {@code param}, with its label and help;
     *   <li>{@code gpu_memory}: the GPU memory the job needs, from {@code choicesGb}; the booking answers it in
     *       {@code vramNeededMb}, in thousands of MB a GB (8 GB is 8000).
     * </ul>
     */
    record TemplateQuestion(
            @Schema(allowableValues = {"preset", "param", "gpu_memory"})
            String kind,

            String label,
            @Nullable String help,
            @Nullable String name,
            List<PresetOption> options,
            @Nullable String defaultOption,
            @Nullable String param,
            List<Integer> choicesGb,
            @Nullable Integer defaultGb) {

        static TemplateQuestion of(Template template, Template.Question question) {
            return switch (question) {
                case Template.Question.Preset preset ->
                    new TemplateQuestion(
                            "preset",
                            preset.label(),
                            preset.help(),
                            preset.name(),
                            preset.options().stream()
                                    .map(option -> new PresetOption(
                                            option.id(), option.label(), option.minutes(), option.sets()))
                                    .toList(),
                            preset.defaultOption(),
                            null,
                            List.of(),
                            null);
                case Template.Question.Basic basic -> {
                    var param = template.params().stream()
                            .filter(p -> p.name().equals(basic.param()))
                            .findFirst()
                            .orElseThrow();
                    yield new TemplateQuestion(
                            "param", param.label(), param.help(), null, List.of(), null, param.name(), List.of(), null);
                }
                case Template.Question.GpuMemory gpu ->
                    new TemplateQuestion(
                            "gpu_memory",
                            gpu.label(),
                            gpu.help(),
                            null,
                            List.of(),
                            null,
                            null,
                            gpu.choicesGb(),
                            gpu.defaultGb());
            };
        }
    }

    /**
     * The GPU memory a model needs, from its size (ADR 0022): {@code baseMb + perGbMb} for each GiB of the upload given
     * as {@code input}, never below the template's minimum, rounded up to the next GPU class (8, 12, 16 or 24 GB,
     * asked for as 8,000, 12,000, 16,000 or 24,000 MB). A link's size isn't known: it needs the template's minimum.
     */
    record VramFromInputView(String input, int baseMb, int perGbMb) {

        static VramFromInputView of(Template.VramFromInput rule) {
            return new VramFromInputView(rule.input(), rule.baseMb(), rule.perGbMb());
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
