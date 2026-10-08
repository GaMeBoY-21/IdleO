package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Service;
import com.idleo.controlplane.sessions.Template;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The template catalog (ADR 0015): templates/catalog.yaml, reviewed in git and bundled in the jar, is the
 * only source of what renters can run. Loaded once at startup; a malformed entry, an unknown field, or an image not
 * pinned by digest, stops the application from starting. Each template describes its parameters and input once
 * (ADR 0019); a parameter's default must itself pass the parameter's rules. Templates are grouped into services
 * (ADR 0021), listed first; a template naming a service that isn't there stops the start too.
 */
@Component
class TemplateCatalog {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern PINNED_IMAGE = Pattern.compile("\\S+@sha256:[0-9a-f]{64}");
    private static final Pattern PARAM_NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

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
            "input");
    private static final Set<String> PARAM_FIELDS =
            Set.of("name", "label", "help", "type", "required", "default", "min", "max", "max_length", "choices");
    private static final Set<String> INPUT_FIELDS = Set.of("accepts", "upload", "url", "max_size_mb", "label", "help");
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
        var ids = new HashSet<String>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalStateException("Every template catalog entry must be a map.");
            }
            var template = template(fields);
            if (!ids.add(template.id())) {
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
            var id = text(fields, "id", "?");
            var where = "Service " + id;
            if (!ID.matcher(id).matches()) {
                throw new IllegalStateException(where + ": the id must be lowercase letters, digits and hyphens.");
            }
            known(fields, SERVICE_FIELDS, where);
            var icon = text(fields, "icon", id);
            if (!Service.ICONS.contains(icon)) {
                throw new IllegalStateException(where + ": icon must be one of " + Service.ICONS + ".");
            }
            var service = new Service(
                    id,
                    text(fields, "name", id),
                    text(fields, "tagline", id),
                    text(fields, "running_label", id),
                    icon,
                    flag(fields, "hidden", where));
            if (services.put(id, service) != null) {
                throw new IllegalStateException(where + " appears twice.");
            }
        }
        return services;
    }

    private static Template template(Map<?, ?> fields) {
        var id = text(fields, "id", "?");
        if (!ID.matcher(id).matches()) {
            throw new IllegalStateException("Template id " + id + " must be lowercase letters, digits and hyphens.");
        }
        known(fields, TEMPLATE_FIELDS, "Template " + id);
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
        int scratchMb = positive(fields, "scratch_mb", id);
        return new Template(
                id,
                text(fields, "service", id),
                text(fields, "name", id),
                text(fields, "description", id),
                image,
                args,
                positive(fields, "min_vram_mb", id),
                defaultMaxRuntime,
                maxRuntimeCap,
                fields.containsKey("typical_runtime") ? duration(fields, "typical_runtime", id) : null,
                positive(fields, "memory_mb", id),
                positive(fields, "cpu_millicores", id),
                positive(fields, "pids_limit", id),
                scratchMb,
                duration(fields, "checkpoint_interval", id),
                params(fields.get("params"), id),
                input(fields.get("input"), id, scratchMb));
    }

    private static List<Template.Param> params(@Nullable Object value, String id) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalStateException("Template " + id + ": params must be a list.");
        }
        var params = new ArrayList<Template.Param>();
        var names = new HashSet<String>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalStateException("Template " + id + ": every parameter must be a map.");
            }
            var param = param(fields, id);
            if (!names.add(param.name())) {
                throw new IllegalStateException("Template " + id + ": parameter " + param.name() + " appears twice.");
            }
            params.add(param);
        }
        return params;
    }

    private static Template.Param param(Map<?, ?> fields, String id) {
        var name = text(fields, "name", id);
        var where = "Template " + id + ", parameter " + name;
        if (!PARAM_NAME.matcher(name).matches()) {
            throw new IllegalStateException(where + ": the name must match " + PARAM_NAME.pattern() + ".");
        }
        known(fields, PARAM_FIELDS, where);
        Template.ParamType type;
        try {
            type = Template.ParamType.valueOf(text(fields, "type", id).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": type must be string, integer, number, boolean or choice.");
        }
        boolean numeric = type == Template.ParamType.INTEGER || type == Template.ParamType.NUMBER;
        only(fields, "min", numeric, where, "integer and number parameters");
        only(fields, "max", numeric, where, "integer and number parameters");
        only(fields, "max_length", type == Template.ParamType.STRING, where, "string parameters");
        only(fields, "choices", type == Template.ParamType.CHOICE, where, "choice parameters");
        var min = decimal(fields, "min", where);
        var max = decimal(fields, "max", where);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalStateException(where + ": min is above max.");
        }
        Integer maxLength = null;
        if (fields.get("max_length") != null) {
            if (!(fields.get("max_length") instanceof Integer length)
                    || length < 1
                    || length > SessionParams.MAX_LENGTH_CAP) {
                throw new IllegalStateException(
                        where + ": max_length must be from 1 to " + SessionParams.MAX_LENGTH_CAP + ".");
            }
            maxLength = length;
        }
        var choices = new ArrayList<String>();
        if (type == Template.ParamType.CHOICE) {
            if (!(fields.get("choices") instanceof List<?> listed)) {
                throw new IllegalStateException(where + ": a choice needs choices.");
            }
            listed.forEach(choice -> choices.add(String.valueOf(choice)));
            if (choices.size() < 2 || new HashSet<>(choices).size() != choices.size() || choices.contains("")) {
                throw new IllegalStateException(where + ": choices must be at least 2, different and non-empty.");
            }
        }
        if (fields.get("required") != null && !(fields.get("required") instanceof Boolean)) {
            throw new IllegalStateException(where + ": required must be true or false.");
        }
        boolean required = Boolean.TRUE.equals(fields.get("required"));
        var help = fields.get("help") == null ? null : text(fields, "help", id);
        var param = new Template.Param(
                name, text(fields, "label", id), help, type, required, null, min, max, maxLength, choices);
        if (fields.get("default") == null) {
            return param;
        }
        if (required) {
            throw new IllegalStateException(where + ": a required parameter has no default.");
        }
        try {
            var defaultValue = SessionParams.check(param, fields.get("default"));
            return new Template.Param(
                    name, param.label(), help, type, false, defaultValue, min, max, maxLength, choices);
        } catch (SessionParams.Invalid invalid) {
            throw new IllegalStateException(where + ": the default " + invalid.getMessage());
        }
    }

    private static Template.Input input(@Nullable Object value, String id, int scratchMb) {
        if (value == null) {
            return Template.Input.NONE;
        }
        if (!(value instanceof Map<?, ?> fields)) {
            throw new IllegalStateException("Template " + id + ": input must be a map.");
        }
        var where = "Template " + id + ", input";
        known(fields, INPUT_FIELDS, where);
        Template.Accepts accepts;
        try {
            accepts = Template.Accepts.valueOf(text(fields, "accepts", id).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": accepts must be none, optional or required.");
        }
        if (accepts == Template.Accepts.NONE) {
            if (fields.size() > 1) {
                throw new IllegalStateException(where + ": with accepts: none, nothing else belongs here.");
            }
            return Template.Input.NONE;
        }
        boolean upload = flag(fields, "upload", where);
        boolean url = flag(fields, "url", where);
        if (!upload && !url) {
            throw new IllegalStateException(where + ": it must take an upload, a URL or both.");
        }
        Integer maxSizeMb = null;
        if (upload) {
            maxSizeMb = positive(fields, "max_size_mb", id);
            if (maxSizeMb > scratchMb) {
                throw new IllegalStateException(where + ": max_size_mb is above the template's scratch_mb.");
            }
        } else if (fields.containsKey("max_size_mb")) {
            throw new IllegalStateException(where + ": max_size_mb is only for uploads.");
        }
        var help = fields.get("help") == null ? null : text(fields, "help", id);
        return new Template.Input(accepts, upload, url, maxSizeMb, text(fields, "label", id), help);
    }

    private static void known(Map<?, ?> fields, Set<String> allowed, String where) {
        for (var key : fields.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalStateException(where + ": unknown field " + key + ".");
            }
        }
    }

    private static void only(Map<?, ?> fields, String name, boolean allowed, String where, String forWhat) {
        if (!allowed && fields.containsKey(name)) {
            throw new IllegalStateException(where + ": " + name + " is only for " + forWhat + ".");
        }
    }

    private static boolean flag(Map<?, ?> fields, String name, String where) {
        var value = fields.get(name);
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalStateException(where + ": " + name + " must be true or false.");
        }
        return Boolean.TRUE.equals(value);
    }

    private static @Nullable BigDecimal decimal(Map<?, ?> fields, String name, String where) {
        var value = fields.get(name);
        return switch (value) {
            case null -> null;
            case Integer whole -> BigDecimal.valueOf(whole);
            case Long whole -> BigDecimal.valueOf(whole);
            case java.math.BigInteger whole -> new BigDecimal(whole);
            case Double real -> BigDecimal.valueOf(real);
            default -> throw new IllegalStateException(where + ": " + name + " must be a number.");
        };
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
