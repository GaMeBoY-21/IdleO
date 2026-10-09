package com.idleo.controlplane.sessions.internal;

import static com.idleo.controlplane.sessions.internal.CatalogFields.flag;
import static com.idleo.controlplane.sessions.internal.CatalogFields.known;
import static com.idleo.controlplane.sessions.internal.CatalogFields.optionalText;
import static com.idleo.controlplane.sessions.internal.CatalogFields.text;

import com.idleo.controlplane.sessions.Template;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A template's named inputs in the catalog (ADR 0022): what the renter brings, each an upload, a link, or either.
 * Renters bring their own model, data and code; nothing here supplies one.
 */
final class CatalogInputs {

    /** A gigabyte as uploads count it: 2^30 bytes. */
    static final long GIB = 1L << 30;

    private static final Set<String> FIELDS =
            Set.of("name", "label", "help", "kind", "accepts", "required", "max_size_gb");
    private static final List<String> SOURCES = List.of("upload", "link");

    private CatalogInputs() {}

    /** {@code vram_from_input}, naming an input that takes uploads; null when it's left out. */
    static Template.@Nullable VramFromInput vramFromInput(
            Map<?, ?> fields, List<Template.Input> inputs, String template) {
        var value = fields.get("vram_from_input");
        if (value == null) {
            return null;
        }
        var where = template + ", vram_from_input";
        if (!(value instanceof Map<?, ?> vram)) {
            throw new IllegalStateException(where + ": must be a map.");
        }
        known(vram, Set.of("input", "base_mb", "per_gb_mb"), where);
        var input = text(vram, "input", where);
        if (inputs.stream().noneMatch(found -> found.name().equals(input) && found.upload())) {
            throw new IllegalStateException(where + ": input " + input + " isn't an input that takes an upload.");
        }
        return new Template.VramFromInput(
                input,
                CatalogFields.positive(vram, "base_mb", where),
                CatalogFields.positive(vram, "per_gb_mb", where));
    }

    static List<Template.Input> read(Map<?, ?> template, String where) {
        var inputs = new ArrayList<Template.Input>();
        var names = new HashSet<String>();
        for (var fields : CatalogFields.maps(template, "inputs", where)) {
            var input = input(fields, where);
            if (!names.add(input.name())) {
                throw new IllegalStateException(where + ": input " + input.name() + " appears twice.");
            }
            inputs.add(input);
        }
        return inputs;
    }

    private static Template.Input input(Map<?, ?> fields, String template) {
        var name = text(fields, "name", template);
        var where = template + ", input " + name;
        if (!CatalogParams.NAME.matcher(name).matches()) {
            throw new IllegalStateException(where + ": the name must match " + CatalogParams.NAME.pattern() + ".");
        }
        known(fields, FIELDS, where);
        Template.InputKind kind;
        try {
            kind = Template.InputKind.valueOf(text(fields, "kind", where).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": kind must be folder or file.");
        }
        var accepts = CatalogFields.strings(fields, "accepts", where);
        if (accepts.isEmpty() || !SOURCES.containsAll(accepts) || new HashSet<>(accepts).size() != accepts.size()) {
            throw new IllegalStateException(where + ": accepts must list upload, link or both.");
        }
        boolean upload = accepts.contains("upload");
        Long maxSizeBytes = null;
        if (upload) {
            var gigabytes = CatalogFields.decimal(fields, "max_size_gb", where);
            if (gigabytes == null || gigabytes.signum() <= 0) {
                throw new IllegalStateException(where + ": an input that takes an upload needs max_size_gb above 0.");
            }
            maxSizeBytes = gigabytes.multiply(BigDecimal.valueOf(GIB)).longValue();
        } else if (fields.containsKey("max_size_gb")) {
            throw new IllegalStateException(where + ": max_size_gb is only for an input that takes an upload.");
        }
        return new Template.Input(
                name,
                text(fields, "label", where),
                optionalText(fields, "help", where),
                kind,
                upload,
                accepts.contains("link"),
                flag(fields, "required", where),
                maxSizeBytes);
    }
}
