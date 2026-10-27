package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.images.CheckedImages;
import com.idleo.controlplane.sessions.Service;
import com.idleo.controlplane.sessions.Template;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What renters can run (ADR 0015), with what each template takes (ADR 0019, ADR 0022): its inputs, plain questions,
 * parameters and budget chips, the one description the web form, the CLI's flags and the SDK's arguments are all made
 * from.
 */
@RestController
@RequestMapping("/v1/templates")
class TemplateController {

    private final TemplateCatalog catalog;
    private final CheckedImages images;

    TemplateController(TemplateCatalog catalog, CheckedImages images) {
        this.catalog = catalog;
        this.images = images;
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
     * @param inputs what the renter brings, in the order the booking screen asks for them
     * @param questions the plain questions, asked after the inputs, in this order
     * @param budgetChips the spending caps, in paise, the booking screen offers beside "Other amount"
     * @param defaultBudgetPaise the chip chosen at first; null when there are no chips
     * @param vramFromInput how the GPU memory needed grows with the renter's uploaded model; null when it doesn't
     * @param youGet what the renter gets back, in a few words ("A LoRA adapter for your model"); null if it doesn't say
     * @param image the container image it runs, pinned by digest; null when it runs the renter's own
     * @param ownImage it runs the renter's own image (ADR 0042): a booking names one by {@code imageId}, checked first
     */
    record TemplateResponse(
            String id,
            String service,
            String name,
            String description,
            @Nullable String image,
            int minVramMb,
            long defaultMaxRuntimeSeconds,
            long maxRuntimeCapSeconds,
            @Nullable Long typicalRuntimeSeconds,
            List<TemplateViews.TemplateParam> params,
            List<TemplateViews.TemplateInput> inputs,
            List<TemplateViews.TemplateQuestion> questions,
            List<Long> budgetChips,
            @Nullable Long defaultBudgetPaise,
            TemplateViews.@Nullable VramFromInputView vramFromInput,
            @Nullable String youGet,
            boolean ownImage) {

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
                    template.params().stream()
                            .map(TemplateViews.TemplateParam::of)
                            .toList(),
                    template.inputs().stream()
                            .map(TemplateViews.TemplateInput::of)
                            .toList(),
                    template.questions().stream()
                            .map(question -> TemplateViews.TemplateQuestion.of(template, question))
                            .toList(),
                    template.budgetChips(),
                    template.defaultBudget(),
                    template.vramFromInput() == null
                            ? null
                            : TemplateViews.VramFromInputView.of(template.vramFromInput()),
                    template.youGet(),
                    template.ownImage());
        }
    }

    /** @param services the services that have at least one template, in the catalog's order */
    record TemplateList(List<ServiceResponse> services, List<TemplateResponse> templates) {}

    @GetMapping
    TemplateList list() {
        return new TemplateList(
                catalog.services().stream().map(ServiceResponse::of).toList(),
                catalog.all().stream()
                        .filter(template -> images.enabled() || !template.ownImage())
                        .map(TemplateResponse::of)
                        .toList());
    }
}
