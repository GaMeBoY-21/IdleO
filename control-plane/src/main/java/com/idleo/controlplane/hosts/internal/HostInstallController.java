package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The setup app's measurements (see {@link HostInstalls}), with no sign-in and limited per address like every
 * signed-out call. A request holds at most 20 events in at most 16 KiB; the body is read only that far, so a larger
 * one is refused without being read in full.
 */
@RestController
class HostInstallController {

    static final int MAX_BODY_BYTES = 16 * 1024;

    private final HostInstalls installs;
    private final Validator validator;
    private final JsonMapper json = JsonMapper.builder().build();

    HostInstallController(HostInstalls installs, Validator validator) {
        this.installs = installs;
        this.validator = validator;
    }

    /**
     * One step of an install. {@code step} is a name such as {@code setup_opened}, {@code checks}, {@code click} or
     * {@code listed}; {@code outcome} started, passed, failed or fixed; {@code reason} why it failed, or which button
     * a click was. Nothing that names the person or the machine: no user name, path or serial.
     */
    record InstallEvent(
            @NotBlank @Size(max = 40) String step,

            @NotNull String outcome,

            @Nullable @Size(max = 120) String reason,

            @Nullable @Min(0) @Max(86_400_000) Long durationMs,
            @NotNull Instant at,

            @Nullable @Size(max = 40) String setupVersion,

            @Nullable @Min(0) @Max(1_000_000) Integer windowsBuild,
            @Nullable @Size(max = 100) String gpuModel) {}

    record InstallEventsRequest(
            @NotNull @NotEmpty @Size(max = HostInstalls.MAX_PER_REQUEST)
            List<@Valid @NotNull InstallEvent> events) {}

    @PostMapping("/v1/host-installs/{installId}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = InstallEventsRequest.class)))
    void record(@PathVariable UUID installId, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        byte[] body;
        try (var in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        InstallEventsRequest parsed;
        try {
            parsed = json.readValue(body, InstallEventsRequest.class);
        } catch (JacksonException e) {
            throw ApiProblem.of(Problem.MALFORMED_REQUEST, "The body isn't the events JSON this call takes.");
        }
        var errors = new java.util.ArrayList<ApiProblem.FieldError>();
        validator
                .validate(parsed)
                .forEach(v ->
                        errors.add(new ApiProblem.FieldError(v.getPropertyPath().toString(), v.getMessage())));
        if (errors.isEmpty()) {
            errors.addAll(formats(parsed.events()));
        }
        if (!errors.isEmpty()) {
            throw ApiProblem.invalid(
                    "Some events are invalid.",
                    errors.stream()
                            .sorted(java.util.Comparator.comparing(ApiProblem.FieldError::field))
                            .toList());
        }
        installs.record(
                installId,
                parsed.events().stream()
                        .map(event -> new HostInstalls.Event(
                                event.step(),
                                event.outcome(),
                                event.reason(),
                                event.durationMs(),
                                event.at(),
                                event.setupVersion(),
                                event.windowsBuild(),
                                event.gpuModel() == null
                                        ? null
                                        : event.gpuModel().strip()))
                        .toList());
    }

    private static final java.util.regex.Pattern STEP = java.util.regex.Pattern.compile("[a-z0-9_]+");
    private static final java.util.regex.Pattern OUTCOME =
            java.util.regex.Pattern.compile("started|passed|failed|fixed");
    private static final java.util.regex.Pattern REASON = java.util.regex.Pattern.compile("[a-z0-9_:.\\-]+");
    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("[0-9A-Za-z.+\\-]+");

    /**
     * The fields' forms, checked here rather than in the contract: a step, a reason and a version are short words, so
     * a path or a name can't be sent in one.
     */
    private static List<ApiProblem.FieldError> formats(List<InstallEvent> events) {
        var errors = new java.util.ArrayList<ApiProblem.FieldError>();
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            var at = "events[" + i + "].";
            if (!STEP.matcher(event.step()).matches()) {
                errors.add(new ApiProblem.FieldError(at + "step", "must be lowercase letters, digits and _."));
            }
            if (!OUTCOME.matcher(event.outcome()).matches()) {
                errors.add(new ApiProblem.FieldError(at + "outcome", "must be started, passed, failed or fixed."));
            }
            if (event.reason() != null && !REASON.matcher(event.reason()).matches()) {
                errors.add(new ApiProblem.FieldError(at + "reason", "must be lowercase letters, digits and _:.-"));
            }
            if (event.setupVersion() != null
                    && !VERSION.matcher(event.setupVersion()).matches()) {
                errors.add(new ApiProblem.FieldError(at + "setupVersion", "must be a version."));
            }
        }
        return errors;
    }

    private static ApiProblem tooLarge() {
        return ApiProblem.of(
                Problem.REQUEST_TOO_LARGE,
                "At most " + HostInstallController.MAX_BODY_BYTES / 1024 + " KiB of events per request.");
    }
}
