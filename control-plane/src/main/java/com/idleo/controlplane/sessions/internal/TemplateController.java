package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Template;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What renters can run (ADR 0015). */
@RestController
@RequestMapping("/v1/templates")
class TemplateController {

    private final TemplateCatalog catalog;

    TemplateController(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    record TemplateResponse(
            String id,
            String name,
            String image,
            int minVramMb,
            long defaultMaxRuntimeSeconds,
            long maxRuntimeCapSeconds) {

        static TemplateResponse of(Template template) {
            return new TemplateResponse(
                    template.id(),
                    template.name(),
                    template.image(),
                    template.minVramMb(),
                    template.defaultMaxRuntime().toSeconds(),
                    template.maxRuntimeCap().toSeconds());
        }
    }

    record TemplateList(List<TemplateResponse> templates) {}

    @GetMapping
    TemplateList list() {
        return new TemplateList(catalog.all().stream().map(TemplateResponse::of).toList());
    }
}
