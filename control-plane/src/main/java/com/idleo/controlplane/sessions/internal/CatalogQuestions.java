package com.idleo.controlplane.sessions.internal;

import static com.idleo.controlplane.sessions.internal.CatalogFields.known;
import static com.idleo.controlplane.sessions.internal.CatalogFields.optionalText;
import static com.idleo.controlplane.sessions.internal.CatalogFields.text;

import com.idleo.controlplane.sessions.GpuClasses;
import com.idleo.controlplane.sessions.Template;
import com.idleo.controlplane.sessions.Template.Question;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A template's plain questions and budget chips in the catalog (ADR 0022). A question is one of:
 *
 * <ul>
 *   <li>{@code preset: <name>}: a choice of options, each setting advanced parameters by name; the control plane
 *       refuses to start if an option names a parameter that isn't there, or a value that doesn't fit it;
 *   <li>{@code param: <name>}: a basic parameter, asked here; every basic parameter is asked exactly once;
 *   <li>{@code gpu_memory: true}: the GPU memory the job needs, as a choice of {@link GpuClasses}.
 * </ul>
 */
final class CatalogQuestions {

    private static final Pattern OPTION_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Set<String> PRESET_FIELDS = Set.of("preset", "label", "help", "options", "default");
    private static final Set<String> OPTION_FIELDS = Set.of("id", "label", "minutes", "sets");
    private static final Set<String> GPU_FIELDS = Set.of("gpu_memory", "label", "help", "choices_gb", "default_gb");
    private static final int MAX_CHIPS = 6;

    private CatalogQuestions() {}

    static List<Question> read(Map<?, ?> template, List<Template.Param> params, int minVramMb, String where) {
        var questions = new ArrayList<Question>();
        var names = new HashSet<String>();
        var asked = new HashSet<String>();
        for (var fields : CatalogFields.maps(template, "questions", where)) {
            Question question;
            if (fields.containsKey("preset")) {
                question = preset(fields, params, where);
                if (!names.add(((Question.Preset) question).name())) {
                    throw new IllegalStateException(where + ": question " + fields.get("preset") + " appears twice.");
                }
            } else if (fields.containsKey("param")) {
                known(fields, Set.of("param"), where + ", question " + fields.get("param"));
                var name = text(fields, "param", where);
                var param = params.stream().filter(p -> p.name().equals(name)).findFirst();
                if (param.isEmpty() || param.get().group() != Template.Group.BASIC) {
                    throw new IllegalStateException(where + ": question param " + name + " isn't a basic parameter.");
                }
                if (!asked.add(name)) {
                    throw new IllegalStateException(where + ": parameter " + name + " is asked twice.");
                }
                question = new Question.Basic(name);
            } else if (fields.containsKey("gpu_memory")) {
                question = gpuMemory(fields, minVramMb, where);
                if (!names.add("gpu_memory")) {
                    throw new IllegalStateException(where + ": GPU memory is asked twice.");
                }
            } else {
                throw new IllegalStateException(where + ": every question is a preset, a param or gpu_memory.");
            }
            questions.add(question);
        }
        params.stream()
                .filter(param -> param.group() == Template.Group.BASIC && !asked.contains(param.name()))
                .findFirst()
                .ifPresent(param -> {
                    throw new IllegalStateException(where + ": basic parameter " + param.name()
                            + " must be asked as a question (- param: " + param.name() + ").");
                });
        return questions;
    }

    private static Question.Preset preset(Map<?, ?> fields, List<Template.Param> params, String template) {
        var name = text(fields, "preset", template);
        var where = template + ", preset " + name;
        if (!CatalogParams.NAME.matcher(name).matches()) {
            throw new IllegalStateException(where + ": the name must match " + CatalogParams.NAME.pattern() + ".");
        }
        known(fields, PRESET_FIELDS, where);
        var options = new ArrayList<Question.Option>();
        var ids = new HashSet<String>();
        for (var option : CatalogFields.maps(fields, "options", where)) {
            var read = option(option, params, where);
            if (!ids.add(read.id())) {
                throw new IllegalStateException(where + ": option " + read.id() + " appears twice.");
            }
            options.add(read);
        }
        if (options.size() < 2) {
            throw new IllegalStateException(where + ": a preset needs at least 2 options.");
        }
        var defaultOption = text(fields, "default", where);
        if (!ids.contains(defaultOption)) {
            throw new IllegalStateException(where + ": the default must be one of its options.");
        }
        return new Question.Preset(
                name, text(fields, "label", where), optionalText(fields, "help", where), options, defaultOption);
    }

    private static Question.Option option(Map<?, ?> fields, List<Template.Param> params, String preset) {
        var id = text(fields, "id", preset);
        var where = preset + ", option " + id;
        if (!OPTION_ID.matcher(id).matches()) {
            throw new IllegalStateException(where + ": the id must match " + OPTION_ID.pattern() + ".");
        }
        known(fields, OPTION_FIELDS, where);
        Integer minutes = fields.containsKey("minutes") ? CatalogFields.positive(fields, "minutes", where) : null;
        if (!(fields.get("sets") instanceof Map<?, ?> sets) || sets.isEmpty()) {
            throw new IllegalStateException(where + ": sets must name at least one parameter.");
        }
        var values = new LinkedHashMap<String, Object>();
        for (var entry : sets.entrySet()) {
            var name = String.valueOf(entry.getKey());
            var param = params.stream()
                    .filter(p -> p.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            where + ": sets " + name + ", which isn't a " + "parameter of the template."));
            if (param.group() != Template.Group.ADVANCED) {
                throw new IllegalStateException(
                        where + ": sets " + name + ", but a preset sets advanced parameters " + "only.");
            }
            try {
                values.put(name, SessionParams.check(param, entry.getValue()));
            } catch (SessionParams.Invalid invalid) {
                throw new IllegalStateException(where + ": its " + name + " " + invalid.getMessage());
            }
        }
        return new Question.Option(id, text(fields, "label", where), minutes, values);
    }

    private static Question.GpuMemory gpuMemory(Map<?, ?> fields, int minVramMb, String template) {
        var where = template + ", question gpu_memory";
        known(fields, GPU_FIELDS, where);
        if (!Boolean.TRUE.equals(fields.get("gpu_memory"))) {
            throw new IllegalStateException(where + ": write it as gpu_memory: true.");
        }
        var choices = new ArrayList<Integer>();
        if (fields.get("choices_gb") instanceof List<?> listed) {
            for (var choice : listed) {
                if (!(choice instanceof Integer gb) || !GpuClasses.GB.contains(gb) || choices.contains(gb)) {
                    throw new IllegalStateException(
                            where + ": choices_gb must be different classes from " + GpuClasses.GB + ".");
                }
                if (GpuClasses.mb(gb) < minVramMb) {
                    throw new IllegalStateException(where + ": " + gb + " GB is below the template's min_vram_mb.");
                }
                choices.add(gb);
            }
        }
        if (choices.size() < 2) {
            throw new IllegalStateException(where + ": choices_gb needs at least 2 classes.");
        }
        if (!(fields.get("default_gb") instanceof Integer defaultGb) || !choices.contains(defaultGb)) {
            throw new IllegalStateException(where + ": default_gb must be one of choices_gb.");
        }
        return new Question.GpuMemory(
                text(fields, "label", where), optionalText(fields, "help", where), choices, defaultGb);
    }

    /** The budget chips, in paise, rising, and the default among them; both left out means none. */
    record Budget(List<Long> chips, Long defaultChip) {}

    static @Nullable Budget budget(Map<?, ?> template, String where) {
        var listed = template.get("budget_chips");
        var chosen = template.get("default_budget");
        if (listed == null && chosen == null) {
            return null;
        }
        var chips = new ArrayList<Long>();
        if (listed instanceof List<?> values) {
            for (var value : values) {
                if (!(value instanceof Integer paise) || paise <= 0 || (!chips.isEmpty() && paise <= chips.getLast())) {
                    throw new IllegalStateException(where + ": budget_chips must be paise above 0, rising.");
                }
                chips.add(paise.longValue());
            }
        }
        if (chips.isEmpty() || chips.size() > MAX_CHIPS) {
            throw new IllegalStateException(where + ": budget_chips must list 1 to " + MAX_CHIPS + " amounts.");
        }
        if (!(chosen instanceof Integer paise) || !chips.contains(paise.longValue())) {
            throw new IllegalStateException(where + ": default_budget must be one of budget_chips.");
        }
        return new Budget(chips, paise.longValue());
    }
}
