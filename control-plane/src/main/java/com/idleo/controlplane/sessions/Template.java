package com.idleo.controlplane.sessions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the template catalog (templates/catalog.yaml, ADR 0015): what a renter can run, the limits its
 * container gets, and what it takes (ADR 0019, ADR 0022): its named inputs, the plain questions the booking screen
 * asks, its parameters and its budget choices, described once here, from which every surface's form, flags and
 * arguments are made. Renters bring their own model, data and code; a template never supplies a model.
 *
 * @param service the {@link Service} it's one way to run
 * @param description one line on what it does
 * @param typicalRuntime about how long it usually takes, for the booking screen; null if there's no usual
 * @param questions the plain questions, in the order the booking screen asks them, after the inputs
 * @param budgetChips the spending caps the booking screen offers, in paise; empty when it offers none
 * @param defaultBudget the chip chosen at first; null exactly when there are no chips
 * @param vramFromInput how the GPU memory a session needs grows with the renter's uploaded model; null when it
 *     doesn't
 * @param youGet what the renter gets back, in a few words, for the booking screen's summary; null if it doesn't say
 */
public record Template(
        String id,
        String service,
        String name,
        String description,
        String image,
        List<String> args,
        int minVramMb,
        Duration defaultMaxRuntime,
        Duration maxRuntimeCap,
        @Nullable Duration typicalRuntime,
        int memoryMb,
        int cpuMillicores,
        int pidsLimit,
        int scratchMb,
        Duration checkpointInterval,
        List<Param> params,
        List<Input> inputs,
        List<Question> questions,
        List<Long> budgetChips,
        @Nullable Long defaultBudget,
        @Nullable VramFromInput vramFromInput,
        @Nullable String youGet) {

    public Template {
        args = List.copyOf(args);
        params = List.copyOf(params);
        inputs = List.copyOf(inputs);
        questions = List.copyOf(questions);
        budgetChips = List.copyOf(budgetChips);
    }

    /** The input named {@code name}, if the template has one. */
    public Optional<Input> input(String name) {
        return inputs.stream().filter(input -> input.name().equals(name)).findFirst();
    }

    /** The preset question named {@code name}, if the template asks one. */
    public Optional<Question.Preset> preset(String name) {
        return questions.stream()
                .filter(question -> question instanceof Question.Preset preset
                        && preset.name().equals(name))
                .map(Question.Preset.class::cast)
                .findFirst();
    }

    /**
     * The GPU memory a model needs grows with its size (ADR 0022): {@code baseMb + perGbMb} for each GiB of the upload
     * given as {@code input}, never below the template's minimum, rounded up to a {@link GpuClasses} class.
     */
    public record VramFromInput(String input, int baseMb, int perGbMb) {}

    /** What kind of value a parameter takes. */
    public enum ParamType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        CHOICE
    }

    /** Where the booking screen shows a parameter: as a plain question, or behind the one "Advanced" row. */
    public enum Group {
        BASIC,
        ADVANCED
    }

    /**
     * A setting the renter can give the job, which reaches it as the environment variable {@code
     * SESSION_PARAM_<NAME>}.
     *
     * @param name {@code ^[a-z][a-z0-9_]{0,31}$}, unique in its template
     * @param defaultValue used when the renter gives none: a Long, BigDecimal, Boolean or String, by type; null for
     *     none, when an optional parameter is simply left out
     * @param min the least an integer or number may be
     * @param max the most an integer or number may be
     * @param maxLength the longest a string may be, in characters
     * @param choices what a choice may be
     */
    public record Param(
            String name,
            String label,
            @Nullable String help,
            ParamType type,
            Group group,
            boolean required,
            @Nullable Object defaultValue,
            @Nullable BigDecimal min,
            @Nullable BigDecimal max,
            @Nullable Integer maxLength,
            List<String> choices) {

        public Param {
            choices = List.copyOf(choices);
        }
    }

    /** What an input holds: a folder of files (a model, a project), or exactly one file (a dataset, a zip). */
    public enum InputKind {
        FOLDER,
        FILE
    }

    /**
     * Something the renter brings, by name: an upload of theirs, staged by the agent into {@code /work/input/<name>/}
     * ({@code SESSION_INPUT_<NAME>_DIR}), or an https link the job fetches itself inside its sandbox ({@code
     * SESSION_INPUT_<NAME>_URL}).
     *
     * @param name {@code ^[a-z][a-z0-9_]{0,31}$}, unique in its template
     * @param upload it takes an upload
     * @param link it takes a link
     * @param maxSizeBytes the largest upload it takes; null when it takes only a link
     */
    public record Input(
            String name,
            String label,
            @Nullable String help,
            InputKind kind,
            boolean upload,
            boolean link,
            boolean required,
            @Nullable Long maxSizeBytes) {}

    /** One plain question the booking screen asks, after the inputs. */
    public sealed interface Question {

        /**
         * A choice that sets several parameters at once, such as "How thorough?". The booking keeps the chosen
         * option's id, so Run again asks it the same way.
         *
         * @param name {@code ^[a-z][a-z0-9_]{0,31}$}, unique among the template's questions
         * @param defaultOption the id of the option chosen when the renter picks none
         */
        record Preset(String name, String label, @Nullable String help, List<Option> options, String defaultOption)
                implements Question {

            public Preset {
                options = List.copyOf(options);
            }

            public Optional<Option> option(String id) {
                return options.stream().filter(option -> option.id().equals(id)).findFirst();
            }
        }

        /**
         * One option of a preset.
         *
         * @param minutes about how long it typically runs, for the screen's time and price; null if there's no usual
         * @param sets the advanced parameters it sets, by name, each value checked against its parameter
         */
        record Option(String id, String label, @Nullable Integer minutes, Map<String, Object> sets) {

            public Option {
                sets = Map.copyOf(sets);
            }
        }

        /** A basic parameter, asked here as a plain question. */
        record Basic(String param) implements Question {}

        /**
         * How much GPU memory the job needs, asked as a choice of GPU classes; the answer is the booking's {@code
         * vramNeededMb}.
         *
         * @param choicesGb the classes offered, from {@link GpuClasses#GB}
         * @param defaultGb the one chosen at first
         */
        record GpuMemory(String label, @Nullable String help, List<Integer> choicesGb, int defaultGb)
                implements Question {

            public GpuMemory {
                choicesGb = List.copyOf(choicesGb);
            }
        }
    }
}
