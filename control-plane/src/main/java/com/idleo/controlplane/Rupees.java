package com.idleo.controlplane;

/**
 * Integer paise as the rupees people read, ₹1,23,456.78: Indian digit grouping, always two paise digits, a true
 * minus sign for a negative amount. The web app's {@code formatPaise} does the same, and both are checked against
 * the cases in api/money-format.json, so an email and a screen show one figure the same way. Money never passes
 * through floating point.
 */
public final class Rupees {

    private Rupees() {}

    public static String format(long paise) {
        var negative = paise < 0;
        var digits = new StringBuilder(Long.toString(paise).replace("-", ""));
        while (digits.length() < 3) {
            digits.insert(0, '0');
        }
        var whole = digits.substring(0, digits.length() - 2);
        var paiseDigits = digits.substring(digits.length() - 2);
        return (negative ? "−" : "") + "₹" + grouped(whole) + "." + paiseDigits;
    }

    /** 1234567 gives 12,34,567: the last three digits, then pairs. */
    private static String grouped(String whole) {
        if (whole.length() <= 3) {
            return whole;
        }
        var last = whole.substring(whole.length() - 3);
        var rest = whole.substring(0, whole.length() - 3);
        var pairs = new StringBuilder();
        while (rest.length() > 2) {
            pairs.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        return rest + pairs + "," + last;
    }
}
