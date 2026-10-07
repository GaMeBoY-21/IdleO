package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.sessions.Template;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A template's parameters (ADR 0019): the one check of a renter's values against the catalog, which booking and
 * quoting both use, and how the values reach the job, as environment variables and nothing else.
 *
 * <ul>
 *   <li>Each value is checked against its parameter's type and limits; an unknown name is refused, never ignored;
 *       a missing optional one takes its default, or is left out when it has none.
 *   <li>Values are kept as a Long (integer), BigDecimal (number), Boolean or String (string, choice), and become
 *       {@code SESSION_PARAM_<NAME>}: integers as plain decimals, numbers as {@code toPlainString()}, booleans as
 *       {@code true} or {@code false}, strings and choices as given.
 *   <li>Strings refuse control characters other than tab and newline, and all the variables together stay under
 *       32 KiB.
 * </ul>
 */
final class SessionParams {

    static final String PREFIX = "SESSION_PARAM_";
    static final String INPUT_FILE = "SESSION_INPUT_FILE";
    static final String INPUT_URL = "SESSION_INPUT_URL";
    /** Where the agent stages an uploaded input inside the job's scratch disk. */
    static final String INPUT_FOLDER = "/work/input/";
    /** Every variable's name and value together, in UTF-8 bytes. */
    static final int ENV_LIMIT_BYTES = 32 * 1024;

    static final int DEFAULT_MAX_LENGTH = 1_000;
    static final int MAX_LENGTH_CAP = 4_096;

    private SessionParams() {}

    /** Why a value doesn't fit its parameter, for the field's {@code detail}. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String why) {
            super(why, null, false, false);
        }
    }

    /**
     * The renter's values for {@code template}, checked, with defaults filled in, in the catalog's order. Each problem
     * is a {@code validation-failed} error naming its field, {@code params.<name>}.
     *
     * @param given the request's {@code params}: JSON values as Java ones (Number, Boolean, String; null for absent)
     */
    static Map<String, Object> resolve(Template template, @Nullable Map<String, ?> given) {
        var values = given == null ? Map.<String, Object>of() : given;
        var errors = new ArrayList<ApiProblem.FieldError>();
        var known = template.params().stream().map(Template.Param::name).toList();
        values.keySet().stream()
                .filter(name -> !known.contains(name))
                .sorted()
                .forEach(name -> errors.add(new ApiProblem.FieldError(
                        "params." + name, "isn't a parameter of template " + template.id() + ".")));
        var resolved = new LinkedHashMap<String, Object>();
        for (var param : template.params()) {
            var value = values.get(param.name());
            if (value == null) {
                if (param.defaultValue() != null) {
                    resolved.put(param.name(), param.defaultValue());
                } else if (param.required()) {
                    errors.add(new ApiProblem.FieldError("params." + param.name(), "is required."));
                }
                continue;
            }
            try {
                resolved.put(param.name(), check(param, value));
            } catch (Invalid invalid) {
                errors.add(new ApiProblem.FieldError("params." + param.name(), invalid.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiProblem.invalid("The parameters don't fit template " + template.id() + ".", errors);
        }
        return resolved;
    }

    /** One value checked against its parameter, as the kept type; also how the catalog checks a default. */
    static Object check(Template.Param param, Object value) throws Invalid {
        return switch (param.type()) {
            case INTEGER -> {
                var number = switch (value) {
                    case Integer whole -> BigInteger.valueOf(whole);
                    case Long whole -> BigInteger.valueOf(whole);
                    case BigInteger whole -> whole;
                    default -> throw new Invalid("must be a whole number.");
                };
                inRange(param, new BigDecimal(number));
                if (number.bitLength() > 63) {
                    throw new Invalid("is too large.");
                }
                yield number.longValueExact();
            }
            case NUMBER -> {
                var number = switch (value) {
                    case Integer whole -> BigDecimal.valueOf(whole);
                    case Long whole -> BigDecimal.valueOf(whole);
                    case BigInteger whole -> new BigDecimal(whole);
                    case BigDecimal decimal -> decimal;
                    case Double real when Double.isFinite(real) -> BigDecimal.valueOf(real);
                    case Float real when Float.isFinite(real) -> new BigDecimal(Float.toString(real));
                    default -> throw new Invalid("must be a number.");
                };
                inRange(param, number);
                // 0.0002 sent as a double arrives as 2.0E-4; the job sees it as the renter wrote it.
                yield number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean flag)) {
                    throw new Invalid("must be true or false.");
                }
                yield flag;
            }
            case STRING -> {
                if (!(value instanceof String text)) {
                    throw new Invalid("must be a string.");
                }
                int limit = param.maxLength() == null ? DEFAULT_MAX_LENGTH : param.maxLength();
                if (text.codePointCount(0, text.length()) > limit) {
                    throw new Invalid("must be at most " + limit + " characters.");
                }
                if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\t' && c != '\n')) {
                    throw new Invalid("can't contain control characters other than tab and newline.");
                }
                yield text;
            }
            case CHOICE -> {
                if (!(value instanceof String text) || !param.choices().contains(text)) {
                    throw new Invalid("must be one of " + String.join(", ", param.choices()) + ".");
                }
                yield text;
            }
        };
    }

    private static void inRange(Template.Param param, BigDecimal number) throws Invalid {
        if (param.min() != null && number.compareTo(param.min()) < 0) {
            throw new Invalid("must be at least " + param.min().toPlainString() + ".");
        }
        if (param.max() != null && number.compareTo(param.max()) > 0) {
            throw new Invalid("must be at most " + param.max().toPlainString() + ".");
        }
    }

    /**
     * The job's environment: each resolved parameter, and the input as {@code SESSION_INPUT_FILE} (an upload, staged
     * at {@code /work/input/<name>}) or {@code SESSION_INPUT_URL}. Nothing else goes in it.
     */
    static Map<String, String> env(
            Map<String, Object> resolved, @Nullable String inputFileName, @Nullable String inputUrl) {
        var env = new LinkedHashMap<String, String>();
        resolved.forEach((name, value) -> env.put(PREFIX + name.toUpperCase(Locale.ROOT), render(value)));
        if (inputFileName != null) {
            env.put(INPUT_FILE, INPUT_FOLDER + inputFileName);
        }
        if (inputUrl != null) {
            env.put(INPUT_URL, inputUrl);
        }
        long bytes = env.entrySet().stream()
                .mapToLong(entry -> entry.getKey().getBytes(StandardCharsets.UTF_8).length
                        + entry.getValue().getBytes(StandardCharsets.UTF_8).length
                        + 2)
                .sum();
        if (bytes >= ENV_LIMIT_BYTES) {
            throw ApiProblem.invalid(
                    "params", "together come to " + bytes + " bytes; they must stay under " + ENV_LIMIT_BYTES + ".");
        }
        return env;
    }

    static String render(Object value) {
        return switch (value) {
            case BigDecimal decimal -> decimal.toPlainString();
            case Long whole -> Long.toString(whole);
            case Boolean flag -> flag.toString();
            case String text -> text;
            default -> throw new IllegalArgumentException("not a parameter value: " + value.getClass());
        };
    }
}
