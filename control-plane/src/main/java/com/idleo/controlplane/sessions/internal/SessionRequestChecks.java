package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.ApiProblem.FieldError;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.sessions.CreateSessionRequest;
import com.idleo.controlplane.sessions.SessionRequests;
import com.idleo.controlplane.sessions.Template;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one check of a booking against its template (ADR 0019, ADR 0022), for booking and quoting alike.
 *
 * <ul>
 *   <li>The template exists; GPU memory and runtime fit it; the preset answers pass {@link SessionPresets} and the
 *       parameters {@link SessionParams}.
 *   <li>Each input is one the template names, and exactly one of an upload or a link of a kind it takes ({@code
 *       input-not-accepted}); every required one is given ({@code input-required}). Problems name the input, {@code
 *       inputs.<name>}.
 *   <li>An upload must be the renter's own ({@code not-found} otherwise), ready ({@code upload-not-ready}), and no
 *       larger than the input takes ({@code input-too-large}).
 *   <li>A link must pass {@link InputLinks}. The job fetches it inside its sandbox; the agent never does.
 * </ul>
 */
@Component
class SessionRequestChecks implements SessionRequests {

    private final TemplateCatalog catalog;
    private final UploadStore uploads;

    SessionRequestChecks(TemplateCatalog catalog, UploadStore uploads) {
        this.catalog = catalog;
        this.uploads = uploads;
    }

    @Override
    public Checked check(UUID renterId, CreateSessionRequest request, boolean inputMayWait) {
        var template = catalog.find(request.templateId())
                .orElseThrow(
                        () -> ApiProblem.invalid("templateId", "There is no template " + request.templateId() + "."));
        int vram = request.vramNeededMb() == null ? template.minVramMb() : request.vramNeededMb();
        if (vram < template.minVramMb()) {
            throw ApiProblem.invalid(
                    "vramNeededMb", "must be at least the template's " + template.minVramMb() + " MB.");
        }
        var maxRuntime = request.maxRuntimeSeconds() == null
                ? template.defaultMaxRuntime()
                : Duration.ofSeconds(request.maxRuntimeSeconds());
        if (maxRuntime.compareTo(template.maxRuntimeCap()) > 0) {
            throw ApiProblem.invalid(
                    "maxRuntimeSeconds",
                    "must be at most the template's " + template.maxRuntimeCap().toSeconds() + " seconds.");
        }
        var presets = SessionPresets.resolve(template, request.presets());
        var params = SessionParams.resolve(template, request.params(), SessionPresets.values(template, presets));
        var inputs = inputs(renterId, template, request.inputs(), inputMayWait);
        var env = SessionParams.env(
                params,
                inputs.stream()
                        .map(input -> new SessionStore.Input(input.name(), input.uploadId(), input.url()))
                        .toList());
        return new Checked(template, vram, maxRuntime, params, presets, inputs, env);
    }

    /** The inputs given, checked, in the template's order. */
    private List<Input> inputs(
            UUID renterId,
            Template template,
            @Nullable Map<String, CreateSessionRequest.SessionInput> given,
            boolean inputMayWait) {
        var values = given == null ? Map.<String, CreateSessionRequest.SessionInput>of() : given;
        var malformed = new ArrayList<FieldError>();
        values.keySet().stream()
                .filter(name -> template.input(name).isEmpty())
                .sorted()
                .forEach(name -> malformed.add(
                        new FieldError("inputs." + name, "isn't an input of template " + template.id() + ".")));
        values.forEach((name, value) -> {
            if (value == null || (value.uploadId() == null) == (value.url() == null)) {
                malformed.add(new FieldError("inputs." + name, "must be exactly one of an uploadId or a url."));
            }
        });
        if (!malformed.isEmpty()) {
            throw ApiProblem.invalid("The inputs don't fit template " + template.id() + ".", malformed);
        }
        var missing = template.inputs().stream()
                .filter(input -> input.required() && !values.containsKey(input.name()))
                .map(input -> new FieldError("inputs." + input.name(), "is required: " + kinds(input) + "."))
                .toList();
        if (!missing.isEmpty() && !inputMayWait) {
            throw ApiProblem.of(
                    Problem.INPUT_REQUIRED, "Template " + template.id() + " needs every required input.", missing);
        }
        var checked = new ArrayList<Input>();
        for (var input : template.inputs()) {
            var value = values.get(input.name());
            if (value == null) {
                continue;
            }
            var field = "inputs." + input.name();
            if (value.uploadId() != null) {
                if (!input.upload()) {
                    throw notAccepted(field, "takes a link, not an upload.");
                }
                var upload = upload(renterId, value.uploadId(), input, field);
                checked.add(new Input(input.name(), upload.id(), null, upload.sizeBytes()));
            } else {
                var url = java.util.Objects.requireNonNull(value.url());
                if (!input.link()) {
                    throw notAccepted(field, "takes an upload, not a link.");
                }
                InputLinks.problem(url).ifPresent(problem -> {
                    throw ApiProblem.invalid(field + ".url", problem);
                });
                checked.add(new Input(input.name(), null, url, 0));
            }
        }
        return checked;
    }

    private UploadStore.Upload upload(UUID renterId, UUID uploadId, Template.Input input, String field) {
        var upload = uploads.find(uploadId)
                .filter(found -> found.userId().equals(renterId))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
        if (upload.status() != UploadStore.Status.READY) {
            throw ApiProblem.of(
                    Problem.UPLOAD_NOT_READY,
                    "Upload " + uploadId + " is " + UploadService.status(upload)
                            + "; only a ready one can be an input.",
                    List.of(new FieldError(field, "isn't ready.")));
        }
        if (input.kind() == Template.InputKind.FILE && upload.fileCount() != 1) {
            throw ApiProblem.of(
                    Problem.INPUT_NOT_ACCEPTED,
                    input.label() + " takes one file; upload " + uploadId + " has " + upload.fileCount() + ".",
                    List.of(new FieldError(field, "takes one file, not a folder.")));
        }
        long limit = input.maxSizeBytes() == null ? Long.MAX_VALUE : input.maxSizeBytes();
        if (upload.sizeBytes() > limit) {
            throw ApiProblem.of(
                    Problem.INPUT_TOO_LARGE,
                    "Upload " + uploadId + " is " + upload.sizeBytes() + " bytes; " + input.label() + " takes at most "
                            + limit + ".",
                    List.of(new FieldError(field, "is larger than this input takes.")));
        }
        return upload;
    }

    private static ApiProblem notAccepted(String field, String why) {
        return ApiProblem.of(Problem.INPUT_NOT_ACCEPTED, "This input " + why, List.of(new FieldError(field, why)));
    }

    private static String kinds(Template.Input input) {
        if (input.upload() && input.link()) {
            return "an upload or an https link";
        }
        return input.upload() ? "an upload" : "an https link";
    }
}
