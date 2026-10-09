package com.idleo.controlplane.sessions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A booking, and a quote for one (ADR 0019): the same body, checked by the same {@link SessionRequests}, so a request
 * that quotes cleanly books cleanly.
 *
 * @param maxPricePaisePerMin the most a minute the renter will pay; left out, the server uses the highest price among
 *     the GPUs that fit right now (ADR 0021), and refuses with no-gpu-free when none does
 * @param params the template's parameters by name, as GET /v1/templates describes them; a preset's values, then the
 *     defaults, fill the rest
 * @param presets the option chosen for each of the template's preset questions, by question name; left out, a
 *     question takes its default option
 * @param inputs what the renter brings, by the template's input names (ADR 0022)
 */
public record CreateSessionRequest(
        @NotBlank String templateId,
        @Nullable @Min(1) Integer maxPricePaisePerMin,
        @NotNull @Min(1) Long maxSpendPaise,
        @Nullable @Min(1) Integer vramNeededMb,
        @Nullable @Min(1) Integer maxRuntimeSeconds,

        @Nullable
        @Schema(
                description = "The template's parameters by name: strings, numbers and booleans. Never put "
                        + "secrets here.",
                additionalPropertiesSchema = ParamValue.class)
        Map<String, Object> params,

        @Nullable Map<String, String> presets,

        @Nullable Map<String, @Valid SessionInput> inputs) {

    /**
     * One input: exactly one of a ready upload of the renter's, or an https link the job fetches itself inside its
     * sandbox.
     */
    public record SessionInput(
            @Nullable UUID uploadId, @Nullable String url) {}

    /** A parameter's value. */
    @Schema(types = {"string", "integer", "number", "boolean"})
    public interface ParamValue {}
}
