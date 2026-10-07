package com.idleo.controlplane.sessions.internal;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * An upload's two names (ADR 0019): the original filename, kept for display, and the stored name, which is the
 * object's name in storage and the file's name in the job's input folder.
 */
final class UploadNames {

    static final int MAX_FILENAME_BYTES = 255;

    private UploadNames() {}

    /** Why {@code filename} can't be kept; empty if it can. */
    static Optional<String> problem(String filename) {
        if (filename.isEmpty()) {
            return Optional.of("must not be empty.");
        }
        if (filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
            return Optional.of("must be at most " + MAX_FILENAME_BYTES + " bytes in UTF-8.");
        }
        if (filename.equals(".") || filename.equals("..")) {
            return Optional.of("can't be . or ..");
        }
        if (filename.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c))) {
            return Optional.of("can't contain /, \\, NUL or control characters.");
        }
        return Optional.empty();
    }

    /** The stored name: letters, digits, ".", "_" and "-" kept, every other character "_". */
    static String stored(String filename) {
        var stored = new StringBuilder();
        filename.codePoints()
                .forEach(c -> stored.append(
                        (c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-'))
                                ? (char) c
                                : '_'));
        return stored.toString();
    }
}
