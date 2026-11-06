package com.idleo.controlplane;

import org.jspecify.annotations.Nullable;

/**
 * A value from outside, made safe to log: what a webhook or a request sent can't start a line of its own in the log, so
 * nobody can forge an entry with it.
 */
public final class LogText {

    private static final int MOST = 200;

    private LogText() {}

    /** The value on one line, its line breaks and other control characters each a {@code _}, at most 200 characters. */
    public static String of(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        var line = value.replace('\r', '_').replace('\n', '_').replaceAll("\\p{Cntrl}", "_");
        return line.length() > MOST ? line.substring(0, MOST) + "…" : line;
    }
}
