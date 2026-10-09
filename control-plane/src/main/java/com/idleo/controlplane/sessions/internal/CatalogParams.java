package com.idleo.controlplane.sessions.internal;

import static com.idleo.controlplane.sessions.internal.CatalogFields.decimal;
import static com.idleo.controlplane.sessions.internal.CatalogFields.known;
import static com.idleo.controlplane.sessions.internal.CatalogFields.only;
import static com.idleo.controlplane.sessions.internal.CatalogFields.optionalText;
import static com.idleo.controlplane.sessions.internal.CatalogFields.text;

import com.idleo.controlplane.sessions.Template;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A template's parameters in the catalog (ADR 0019, ADR 0022): each with a type, its limits, and a group, basic (asked
 * as a plain question) or advanced (behind the one "Advanced" row, with its default set). A default must itself pass
 * the parameter's rules.
 */
final class CatalogParams {

    /** A parameter's, an input's or a question's name. */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private static final Set<String> FIELDS = Set.of(
            "name", "label", "help", "type", "group", "required", "default", "min", "max", "max_length", "choices");

    private CatalogParams() {}

    static List<Template.Param> read(Map<?, ?> template, String where) {
        var params = new ArrayList<Template.Param>();
        var names = new HashSet<String>();
        for (var fields : CatalogFields.maps(template, "params", where)) {
            var param = param(fields, where);
            if (!names.add(param.name())) {
                throw new IllegalStateException(where + ": parameter " + param.name() + " appears twice.");
            }
            params.add(param);
        }
        return params;
    }

    private static Template.Param param(Map<?, ?> fields, String template) {
        var name = text(fields, "name", template);
        var where = template + ", parameter " + name;
        if (!NAME.matcher(name).matches()) {
            throw new IllegalStateException(where + ": the name must match " + NAME.pattern() + ".");
        }
        known(fields, FIELDS, where);
        var type = choose(
                Template.ParamType.class,
                text(fields, "type", where),
                where,
                "type",
                "string, integer, " + "number, boolean or choice");
        var group = choose(Template.Group.class, text(fields, "group", where), where, "group", "basic or advanced");
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
        var label = text(fields, "label", where);
        var help = optionalText(fields, "help", where);
        var param = new Template.Param(name, label, help, type, group, required, null, min, max, maxLength, choices);
        if (fields.get("default") == null) {
            if (group == Template.Group.ADVANCED && required) {
                throw new IllegalStateException(
                        where + ": an advanced parameter is never required; give it a " + "default, or make it basic.");
            }
            return param;
        }
        if (required) {
            throw new IllegalStateException(where + ": a required parameter has no default.");
        }
        try {
            var defaultValue = SessionParams.check(param, fields.get("default"));
            return new Template.Param(
                    name, label, help, type, group, false, defaultValue, min, max, maxLength, choices);
        } catch (SessionParams.Invalid invalid) {
            throw new IllegalStateException(where + ": the default " + invalid.getMessage());
        }
    }

    private static <E extends Enum<E>> E choose(
            Class<E> type, String value, String where, String field, String allowed) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": " + field + " must be " + allowed + ".");
        }
    }
}
