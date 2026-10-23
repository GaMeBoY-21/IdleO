package com.idleo.controlplane.billing.internal;

import java.util.regex.Pattern;

/**
 * A GSTIN's shape and check digit: two digits of a state code, a PAN (five letters, four digits, a letter), the
 * entity number (1 to 9 or a letter), {@code Z}, and a check character worked out from the first fourteen, base 36
 * with weights 1 and 2 by turns (the GSTN's published algorithm).
 */
final class Gstin {

    private static final Pattern SHAPE = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final String CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private Gstin() {}

    /** Whether {@code gstin} is a well-formed GSTIN of a known state, with the right check character. */
    static boolean valid(String gstin) {
        if (gstin == null || !SHAPE.matcher(gstin).matches() || !GstStates.known(gstin.substring(0, 2))) {
            return false;
        }
        return checkCharacter(gstin.substring(0, 14)) == gstin.charAt(14);
    }

    /** The check character for the first fourteen characters of a GSTIN. */
    static char checkCharacter(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int value = CHARACTERS.indexOf(first14.charAt(i));
            int product = value * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARACTERS.charAt((36 - sum % 36) % 36);
    }
}
