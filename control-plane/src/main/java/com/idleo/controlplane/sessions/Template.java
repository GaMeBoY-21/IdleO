package com.idleo.controlplane.sessions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the template catalog (templates/catalog.yaml, ADR 0015): what a renter can run, the limits its
 * container gets, and what it takes (ADR 0019): its parameters and its input, described once here, from which every
 * surface's form, flags and arguments are made.
 *
 * @param description one line on what it does
 * @param typicalRuntime about how long it usually takes, for the booking screen; null if there's no usual
 */
public record Template(
        String id,
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
        Input input) {

    public Template {
        args = List.copyOf(args);
        params = List.copyOf(params);
    }

    /** What kind of value a parameter takes. */
    public enum ParamType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        CHOICE
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

    /** Whether a template takes an input. */
    public enum Accepts {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    /**
     * The one file a job reads: an upload, staged by the agent, or an https URL the job fetches itself inside its
     * sandbox.
     *
     * @param maxSizeMb the largest upload it takes, never above the template's scratch disk
     */
    public record Input(
            Accepts accepts,
            boolean upload,
            boolean url,
            @Nullable Integer maxSizeMb,
            @Nullable String label,
            @Nullable String help) {

        public static final Input NONE = new Input(Accepts.NONE, false, false, null, null, null);
    }
}
