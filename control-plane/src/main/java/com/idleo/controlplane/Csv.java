package com.idleo.controlplane;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** The console's CSV files (ADR 0052, ADR 0055): one way to write a cell, whatever the file. */
public final class Csv {

    private Csv() {}

    /** One CSV cell, quoted when it needs it, and never read by a spreadsheet as a formula. */
    public static String cell(@Nullable String value) {
        if (value == null) {
            return "";
        }
        var safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return safe.matches("(?s).*[\",\n\r].*") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    /** One CSV line of these cells, numbers as they are, ending in a newline. */
    public static String line(@Nullable Object... cells) {
        return Arrays.stream(cells)
                        .map(value -> value == null
                                ? ""
                                : value instanceof Number ? value.toString() : cell(value.toString()))
                        .collect(Collectors.joining(","))
                + "\n";
    }
}
