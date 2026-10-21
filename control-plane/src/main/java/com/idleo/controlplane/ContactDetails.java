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

    private static final String EMAIL = "[^\\s@\"]+@[^\\s@\"]+\\.[a-z]{2,}";

    private static final Pattern EMAIL_ADDRESS = Pattern.compile(EMAIL, Pattern.CASE_INSENSITIVE);

    /** An email address, an IPv4 address, or an IPv6 address written in full or with {@code ::}. */
    static final Pattern FOUND = Pattern.compile(
            EMAIL
                    + "|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"
                    + "|\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b"
                    + "|\\b(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4})?\\b",
            Pattern.CASE_INSENSITIVE);

    private ContactDetails() {}

    /** Whether {@code text} holds an email or an IP address. */
    public static boolean found(@Nullable String text) {
        return text != null && FOUND.matcher(text).find();
    }

    /** Whether {@code text} is one email address and nothing else. */
    public static boolean isEmailAddress(@Nullable String text) {
        return text != null && EMAIL_ADDRESS.matcher(text.strip()).matches();
    }

    static final class Validator implements ConstraintValidator<NoContactDetails, String> {
        @Override
        public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
            return !found(value);
        }
    }
}
