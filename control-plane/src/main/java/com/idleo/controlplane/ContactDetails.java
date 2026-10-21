package com.idleo.controlplane;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The one definition of an email or IP address in free text (ADR 0037), which {@link NoContactDetails} and the audit
 * log check.
 */
public final class ContactDetails {

    /** An email address, an IPv4 address, or an IPv6 address written in full or with {@code ::}. */
    static final Pattern FOUND = Pattern.compile(
            "[^\\s@\"]+@[^\\s@\"]+\\.[a-z]{2,}"
                    + "|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"
                    + "|\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b"
                    + "|\\b(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4})?\\b",
            Pattern.CASE_INSENSITIVE);

    private ContactDetails() {}

    /** Whether {@code text} holds an email or an IP address. */
    public static boolean found(@Nullable String text) {
        return text != null && FOUND.matcher(text).find();
    }

    static final class Validator implements ConstraintValidator<NoContactDetails, String> {
        @Override
        public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
            return !found(value);
        }
    }
}
