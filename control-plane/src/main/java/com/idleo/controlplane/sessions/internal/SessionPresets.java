package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.sessions.Template;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A booking's answers to its template's preset questions (ADR 0022): each a choice of options that sets several
 * advanced parameters at once. The chosen option's id is kept with the session, so Run again asks the same way.
 */
final class SessionPresets {

    private SessionPresets() {}

    /**
     * The option of every preset question, in the template's order: the renter's, or the question's default. Each
     * problem is a {@code validation-failed} error naming its question, {@code questions.<name>}.
     */
    static Map<String, String> resolve(Template template, @Nullable Map<String, String> given) {
        var chosen = given == null ? Map.<String, String>of() : given;
        var errors = new ArrayList<ApiProblem.FieldError>();
        chosen.keySet().stream()
                .filter(name -> template.preset(name).isEmpty())
                .sorted()
                .forEach(name -> errors.add(new ApiProblem.FieldError(
                        "questions." + name, "isn't a question of template " + template.id() + ".")));
        var resolved = new LinkedHashMap<String, String>();
        for (var question : template.questions()) {
            if (!(question instanceof Template.Question.Preset preset)) {
                continue;
            }
            var option = chosen.get(preset.name());
            if (option == null) {
                resolved.put(preset.name(), preset.defaultOption());
            } else if (preset.option(option).isPresent()) {
                resolved.put(preset.name(), option);
            } else {
                errors.add(new ApiProblem.FieldError(
                        "questions." + preset.name(),
                        "must be one of "
                                + String.join(
                                        ", ",
                                        preset.options().stream()
                                                .map(Template.Question.Option::id)
                                                .toList())
                                + "."));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiProblem.invalid("The answers don't fit template " + template.id() + ".", errors);
        }
        return resolved;
    }

    /** The parameter values the chosen options set; the catalog checked each against its parameter. */
    static Map<String, Object> values(Template template, Map<String, String> chosen) {
        var values = new LinkedHashMap<String, Object>();
        chosen.forEach((name, option) -> template.preset(name)
                .flatMap(preset -> preset.option(option))
                .ifPresent(found -> values.putAll(found.sets())));
        return values;
    }
}
