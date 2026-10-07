package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Template;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What renters can run (ADR 0015), with each template's parameters and input (ADR 0019): the one description the
 * web form, the CLI's flags and the SDK's arguments are all made from.
 */
@RestController
@RequestMapping("/v1/templates")
class TemplateController {

    private final TemplateCatalog catalog;

    TemplateController(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * A parameter, which reaches the job as {@code SESSION_PARAM_<NAME>}. Never put secrets in one.
     *
     * @param defaultValue what it is when the renter gives none; null when an optional one is simply left out
     * @param maxLength for a string, the most characters it takes
     */
    record TemplateParam(
            String name,
            String label,
            @Nullable String help,

            @Schema(allowableValues = {"string", "integer", "number", "boolean", "choice"})
            String type,

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
                    param.type().name().toLowerCase(Locale.ROOT),
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
     * The one file a job reads: an upload ({@code POST /v1/uploads}), or an https URL the job fetches itself inside
     * its sandbox.
     *
     * @param maxSizeMb the largest upload it takes
     */
    record TemplateInput(
            @Schema(allowableValues = {"none", "optional", "required"})
            String accepts,

            boolean upload,
            boolean url,
            @Nullable Integer maxSizeMb,
            @Nullable String label,
            @Nullable String help) {

        static TemplateInput of(Template.Input input) {
            return new TemplateInput(
                    input.accepts().name().toLowerCase(Locale.ROOT),
                    input.upload(),
                    input.url(),
                    input.maxSizeMb(),
                    input.label(),
                    input.help());
        }
    }

    /** @param typicalRuntimeSeconds about how long it usually takes; null if there's no usual */
    record TemplateResponse(
            String id,
            String name,
            String description,
            String image,
            int minVramMb,
            long defaultMaxRuntimeSeconds,
            long maxRuntimeCapSeconds,
            @Nullable Long typicalRuntimeSeconds,
            List<TemplateParam> params,
            TemplateInput input) {

        static TemplateResponse of(Template template) {
            return new TemplateResponse(
                    template.id(),
                    template.name(),
                    template.description(),
                    template.image(),
                    template.minVramMb(),
                    template.defaultMaxRuntime().toSeconds(),
                    template.maxRuntimeCap().toSeconds(),
                    template.typicalRuntime() == null
                            ? null
                            : template.typicalRuntime().toSeconds(),
                    template.params().stream().map(TemplateParam::of).toList(),
                    TemplateInput.of(template.input()));
        }
    }

    record TemplateList(List<TemplateResponse> templates) {}

    @GetMapping
    TemplateList list() {
        return new TemplateList(catalog.all().stream().map(TemplateResponse::of).toList());
    }
}
