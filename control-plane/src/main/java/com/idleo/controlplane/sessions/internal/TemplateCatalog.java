package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Template;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The template catalog (ADR 0015): templates/catalog.yaml, reviewed in git and bundled in the jar, is the
 * only source of what renters can run. Loaded once at startup; a malformed entry, or an image not pinned
 * by digest, stops the application from starting.
 */
@Component
class TemplateCatalog {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern PINNED_IMAGE = Pattern.compile("\\S+@sha256:[0-9a-f]{64}");

    private final Map<String, Template> templates;

    TemplateCatalog(@Value("${sessions.templates.catalog}") Resource catalog) throws IOException {
        try (var in = catalog.getInputStream()) {
            this.templates = parse(in);
        }
    }

    List<Template> all() {
        return List.copyOf(templates.values());
    }

    Optional<Template> find(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    /** Parses and checks a catalog, refusing it whole if any entry is wrong. */
    static Map<String, Template> parse(InputStream yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof Map<?, ?> top)
                || !(top.get("templates") instanceof List<?> entries)
                || entries.isEmpty()) {
            throw new IllegalStateException("The template catalog needs a non-empty templates list.");
        }
        var templates = new LinkedHashMap<String, Template>();
        var ids = new HashSet<String>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalStateException("Every template catalog entry must be a map.");
            }
            var template = template(fields);
            if (!ids.add(template.id())) {
                throw new IllegalStateException("Template id " + template.id() + " appears twice.");
            }
            templates.put(template.id(), template);
        }
        return templates;
    }

    private static Template template(Map<?, ?> fields) {
        var id = text(fields, "id", "?");
        if (!ID.matcher(id).matches()) {
            throw new IllegalStateException("Template id " + id + " must be lowercase letters, digits and hyphens.");
        }
        var image = text(fields, "image", id);
        if (!PINNED_IMAGE.matcher(image).matches()) {
            throw new IllegalStateException("Template " + id + ": the image must be pinned by digest (@sha256:...).");
        }
        var defaultMaxRuntime = duration(fields, "default_max_runtime", id);
        var maxRuntimeCap = duration(fields, "max_runtime_cap", id);
        if (defaultMaxRuntime.compareTo(maxRuntimeCap) > 0) {
            throw new IllegalStateException("Template " + id + ": default_max_runtime is above max_runtime_cap.");
        }
        var args = new ArrayList<String>();
        if (fields.get("args") instanceof List<?> list) {
            list.forEach(arg -> args.add(String.valueOf(arg)));
        } else if (fields.get("args") != null) {
            throw new IllegalStateException("Template " + id + ": args must be a list.");
        }
        return new Template(
                id,
                text(fields, "name", id),
                image,
                args,
                positive(fields, "min_vram_mb", id),
                defaultMaxRuntime,
                maxRuntimeCap,
                positive(fields, "memory_mb", id),
                positive(fields, "cpu_millicores", id),
                positive(fields, "pids_limit", id),
                positive(fields, "scratch_mb", id),
                duration(fields, "checkpoint_interval", id));
    }

    private static String text(Map<?, ?> fields, String name, String id) {
        if (!(fields.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Template " + id + ": " + name + " is required.");
        }
        return value.strip();
    }

    private static int positive(Map<?, ?> fields, String name, String id) {
        if (!(fields.get(name) instanceof Integer value) || value <= 0) {
            throw new IllegalStateException("Template " + id + ": " + name + " must be a whole number above zero.");
        }
        return value;
    }

    private static Duration duration(Map<?, ?> fields, String name, String id) {
        var text = text(fields, name, id);
        Duration value;
        try {
            value = DurationStyle.detectAndParse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Template " + id + ": " + name + " isn't a duration like 30m.");
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalStateException("Template " + id + ": " + name + " must be above zero.");
        }
        return value;
    }
}
