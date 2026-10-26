package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.images.CheckedImages;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a session of the renter's own image runs (ADR 0042): the command they typed, run with {@code /bin/sh -c} in the
 * image's own working directory, or, left empty, the image's own entrypoint and command. Everything else is the template's
 * and the job contract's, as for every session.
 */
final class OwnImageJobs {

    /** The parameter a renter types their command in. */
    static final String COMMAND = "command";

    static final List<String> SHELL = List.of("/bin/sh", "-c");

    private OwnImageJobs() {}

    /** The command the renter typed, if they typed one. */
    static @Nullable String command(Map<String, Object> params) {
        return params.get(COMMAND) instanceof String command && !command.isBlank() ? command.strip() : null;
    }

    /** The entrypoint to run in place of the image's: the shell for a typed command; none (the image's own) otherwise. */
    static List<String> entrypoint(Map<String, Object> params) {
        return command(params) == null ? List.of() : SHELL;
    }

    /** The entrypoint's arguments: the typed command; none (the image's own command) otherwise. */
    static List<String> args(Map<String, Object> params) {
        var command = command(params);
        return command == null ? List.of() : List.of(command);
    }

    /** Where the job runs: the image's own working directory, or /work (empty) when it names none. */
    static String workingDir(CheckedImages.Image image) {
        return image.workingDir() == null ? "" : image.workingDir();
    }
}
