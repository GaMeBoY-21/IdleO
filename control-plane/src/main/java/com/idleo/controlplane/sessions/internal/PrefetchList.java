package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.ImagesToPrefetch;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The job images worth having on a host before a job needs them (ADR 0035): each image of the template catalog's visible
 * services, once, with the least GPU memory any template that uses it asks for. Hidden services (diagnostics, developer
 * tests) are left out: nobody books them for real, and their images aren't worth a download on every laptop.
 */
@Component
class PrefetchList implements ImagesToPrefetch {

    private final TemplateCatalog catalog;

    PrefetchList(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<Image> images() {
        var visible = catalog.services().stream()
                .filter(service -> !service.hidden())
                .map(service -> service.id())
                .toList();
        var least = new LinkedHashMap<String, Integer>();
        for (var template : catalog.all()) {
            if (visible.contains(template.service())) {
                least.merge(template.image(), template.minVramMb(), Math::min);
            }
        }
        return least.entrySet().stream()
                .map(entry -> new Image(entry.getKey(), entry.getValue()))
                .toList();
    }
}
