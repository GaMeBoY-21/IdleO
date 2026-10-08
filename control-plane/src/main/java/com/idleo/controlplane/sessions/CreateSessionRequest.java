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
 * @param params the template's parameters by name, as GET /v1/templates describes them; defaults fill the rest
 * @param input the file the job reads, when the template takes one
 */
public record CreateSessionRequest(
        @NotBlank String templateId,
        @NotNull @Min(1) Integer maxPricePaisePerMin,
        @NotNull @Min(1) Long maxSpendPaise,
        @Nullable @Min(1) Integer vramNeededMb,
        @Nullable @Min(1) Integer maxRuntimeSeconds,

        @Nullable
        @Schema(
                description = "The template's parameters by name: strings, numbers and booleans. Never put "
                        + "secrets here.",
                additionalPropertiesSchema = ParamValue.class)
        Map<String, Object> params,

        @Nullable @Valid SessionInput input) {

    /**
     * The job's input: exactly one of a ready upload of the renter's, or an https URL the job fetches itself inside
     * its sandbox.
     */
    public record SessionInput(
            @Nullable UUID uploadId, @Nullable String url) {}

    /** A parameter's value. */
    @Schema(types = {"string", "integer", "number", "boolean"})
    public interface ParamValue {}
}
