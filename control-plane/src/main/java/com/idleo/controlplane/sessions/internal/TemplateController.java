package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Service;
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

    /**
     * Something a renter can run (ADR 0021): a group of templates, one tile on Home.
     *
     * @param tagline a few words under its name
     * @param runningLabel what a running session of it is doing ("Fine-tuning")
     * @param icon a key from a fixed list, which each client draws
     * @param hidden kept off Home and Book's list; its templates can still be booked
     */
    record ServiceResponse(
            String id,
            String name,
            String tagline,
            String runningLabel,

            @Schema(
                    allowableValues = {
                        "layers",
                        "code",
                        "image",
                        "mic",
                        "table",
                        "cube",
                        "notebook",
                        "plug",
                        "gamepad",
                        "flask",
                        "pulse"
                    })
            String icon,

            boolean hidden) {

        static ServiceResponse of(Service service) {
            return new ServiceResponse(
                    service.id(),
                    service.name(),
                    service.tagline(),
                    service.runningLabel(),
                    service.icon(),
                    service.hidden());
        }
    }

    /**
     * @param service the service it's one way to run
     * @param typicalRuntimeSeconds about how long it usually takes; null if there's no usual
     */
    record TemplateResponse(
            String id,
            String service,
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
                    template.service(),
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

    /** @param services the services that have at least one template, in the catalog's order */
    record TemplateList(List<ServiceResponse> services, List<TemplateResponse> templates) {}

    @GetMapping
    TemplateList list() {
        return new TemplateList(
                catalog.services().stream().map(ServiceResponse::of).toList(),
                catalog.all().stream().map(TemplateResponse::of).toList());
    }
}
