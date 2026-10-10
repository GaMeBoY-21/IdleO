package com.idleo.controlplane.sessions.internal;

import static com.idleo.controlplane.sessions.internal.CatalogFields.duration;
import static com.idleo.controlplane.sessions.internal.CatalogFields.flag;
import static com.idleo.controlplane.sessions.internal.CatalogFields.known;
import static com.idleo.controlplane.sessions.internal.CatalogFields.optionalText;
import static com.idleo.controlplane.sessions.internal.CatalogFields.positive;
import static com.idleo.controlplane.sessions.internal.CatalogFields.text;

import com.idleo.controlplane.sessions.Service;
import com.idleo.controlplane.sessions.Template;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The template catalog (ADR 0015): templates/catalog.yaml, reviewed in git and bundled in the jar, is the
 * only source of what renters can run. Loaded once at startup; a malformed entry, an unknown field, or an image not
 * pinned by digest, stops the application from starting. Each template describes once what it takes (ADR 0019, ADR
 * 0022): its parameters ({@link CatalogParams}), its named inputs ({@link CatalogInputs}), and its plain questions and
 * budget chips ({@link CatalogQuestions}). Templates are grouped into services (ADR 0021), listed first; a template
 * naming a service that isn't there stops the start too.
 */
@Component
class TemplateCatalog {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern PINNED_IMAGE = Pattern.compile("\\S+@sha256:[0-9a-f]{64}");

    private static final Set<String> TEMPLATE_FIELDS = Set.of(
            "id",
            "service",
            "name",
            "description",
            "image",
            "args",
            "min_vram_mb",
            "default_max_runtime",
            "max_runtime_cap",
            "typical_runtime",
            "memory_mb",
            "cpu_millicores",
            "pids_limit",
            "scratch_mb",
            "checkpoint_interval",
            "params",
            "inputs",
            "questions",
            "budget_chips",
            "default_budget",
            "vram_from_input",
            "you_get");
    private static final Set<String> SERVICE_FIELDS =
            Set.of("id", "name", "tagline", "running_label", "icon", "hidden");

    /** A whole catalog: its services and its templates, each in the file's order. */
    record Parsed(Map<String, Service> services, Map<String, Template> templates) {}

    private final Map<String, Service> services;
    private final Map<String, Template> templates;

    TemplateCatalog(@Value("${sessions.templates.catalog}") Resource catalog) throws IOException {
        try (var in = catalog.getInputStream()) {
            var parsed = read(in);
            this.services = parsed.services();
            this.templates = parsed.templates();
        }
    }

    List<Template> all() {
        return List.copyOf(templates.values());
    }

    /** The services that have at least one template, in the catalog's order; one without a template isn't listed. */
    List<Service> services() {
        var used = new HashSet<String>();
        templates.values().forEach(template -> used.add(template.service()));
        return services.values().stream()
                .filter(service -> used.contains(service.id()))
                .toList();
    }

    Optional<Template> find(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    /** A catalog's templates, parsed and checked. */
    static Map<String, Template> parse(InputStream yaml) {
        return read(yaml).templates();
    }

    /** Parses and checks a catalog, refusing it whole if any entry is wrong. */
    static Parsed read(InputStream yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof Map<?, ?> top)
                || !(top.get("templates") instanceof List<?> entries)
                || entries.isEmpty()) {
            throw new IllegalStateException("The template catalog needs a non-empty templates list.");
        }
        known(top, Set.of("services", "templates"), "The template catalog");
        var services = services(top.get("services"));
        var templates = new LinkedHashMap<String, Template>();
        for (var fields : CatalogFields.maps(top, "templates", "The template catalog")) {
            var template = template(fields);
            if (templates.containsKey(template.id())) {
                throw new IllegalStateException("Template id " + template.id() + " appears twice.");
            }
            if (!services.containsKey(template.service())) {
                throw new IllegalStateException(
                        "Template " + template.id() + ": its service " + template.service() + " isn't in services.");
            }
            templates.put(template.id(), template);
        }
        return new Parsed(services, templates);
    }

    private static Map<String, Service> services(@Nullable Object value) {
        if (!(value instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalStateException("The template catalog needs a non-empty services list.");
        }
        var services = new LinkedHashMap<String, Service>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalStateException("Every service must be a map.");
            }
            var where = "Service " + (fields.get("id") instanceof String id ? id : "?");
            var id = text(fields, "id", where);
            if (!ID.matcher(id).matches()) {
                throw new IllegalStateException(where + ": the id must be lowercase letters, digits and hyphens.");
            }
            known(fields, SERVICE_FIELDS, where);
            var icon = text(fields, "icon", where);
            if (!Service.ICONS.contains(icon)) {
                throw new IllegalStateException(where + ": icon must be one of " + Service.ICONS + ".");
            }
            var service = new Service(
                    id,
                    text(fields, "name", where),
                    text(fields, "tagline", where),
                    text(fields, "running_label", where),
                    icon,
                    flag(fields, "hidden", where));
            if (services.put(id, service) != null) {
                throw new IllegalStateException(where + " appears twice.");
            }
        }
        return services;
    }

    private static Template template(Map<?, ?> fields) {
        var where = "Template " + (fields.get("id") instanceof String id ? id : "?");
        var id = text(fields, "id", where);
        if (!ID.matcher(id).matches()) {
            throw new IllegalStateException("Template id " + id + " must be lowercase letters, digits and hyphens.");
        }
        known(fields, TEMPLATE_FIELDS, where);
        var image = text(fields, "image", where);
        if (!PINNED_IMAGE.matcher(image).matches()) {
            throw new IllegalStateException(where + ": the image must be pinned by digest (@sha256:...).");
        }
        var defaultMaxRuntime = duration(fields, "default_max_runtime", where);
        var maxRuntimeCap = duration(fields, "max_runtime_cap", where);
        if (defaultMaxRuntime.compareTo(maxRuntimeCap) > 0) {
            throw new IllegalStateException(where + ": default_max_runtime is above max_runtime_cap.");
        }
        if (fields.get("args") != null && !(fields.get("args") instanceof List<?>)) {
            throw new IllegalStateException(where + ": args must be a list.");
        }
        int minVramMb = positive(fields, "min_vram_mb", where);
        var params = CatalogParams.read(fields, where);
        var budget = CatalogQuestions.budget(fields, where);
        var inputs = CatalogInputs.read(fields, where);
        return new Template(
                id,
                text(fields, "service", where),
                text(fields, "name", where),
                text(fields, "description", where),
                image,
                CatalogFields.strings(fields, "args", where),
                minVramMb,
                defaultMaxRuntime,
                maxRuntimeCap,
                fields.containsKey("typical_runtime") ? duration(fields, "typical_runtime", where) : null,
                positive(fields, "memory_mb", where),
                positive(fields, "cpu_millicores", where),
                positive(fields, "pids_limit", where),
                positive(fields, "scratch_mb", where),
                duration(fields, "checkpoint_interval", where),
                params,
                inputs,
                CatalogQuestions.read(fields, params, minVramMb, where),
                budget == null ? List.of() : budget.chips(),
                budget == null ? null : budget.defaultChip(),
                CatalogInputs.vramFromInput(fields, inputs, where),
                optionalText(fields, "you_get", where));
    }
}
