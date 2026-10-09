package com.idleo.controlplane.sessions.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An upload's names (ADR 0019, ADR 0022): its name and each file's relative path, kept as the renter gave them for
 * display; and each file's stored path, the object's name in storage under the upload's {@code files/} folder and the
 * file's path in the job's input folder.
 */
final class UploadNames {

    static final int MAX_FILENAME_BYTES = 255;
    /** The longest relative path, in UTF-8 bytes, which keeps every object key well under storage's 1,024. */
    static final int MAX_PATH_BYTES = 900;

    private UploadNames() {}

    /** Why {@code filename}, an upload's name or one segment of a path, can't be kept; empty if it can. */
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

    /**
     * Why {@code path}, a file's path inside its upload, can't be kept; empty if it can. It's relative: segments
     * joined by {@code /}, none empty, none {@code .} or {@code ..}, so it never starts with {@code /}.
     */
    static Optional<String> pathProblem(String path) {
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            return Optional.of("must be at most " + MAX_PATH_BYTES + " bytes in UTF-8.");
        }
        if (path.startsWith("/")) {
            return Optional.of("must be relative, not start with /.");
        }
        for (var segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                return Optional.of("can't have an empty folder name (// or a / at the end).");
            }
            var problem = problem(segment);
            if (problem.isPresent()) {
                return Optional.of("has a part that " + problem.get());
            }
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

    /** The stored path: each segment of a path that passed {@link #pathProblem}, stored. */
    static String storedPath(String path) {
        return Arrays.stream(path.split("/")).map(UploadNames::stored).collect(Collectors.joining("/"));
    }
}
