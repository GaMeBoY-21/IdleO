package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Template;
import java.util.List;
import java.util.Optional;

/**
 * The one check of what an upload holds against its input's rules (ADR 0022): booking and quoting use it, and the web
 * app applies the same rules from {@code GET /v1/templates} before Book, in the same words. A folder is checked by its
 * files' paths; a folder input given one zip, by the names in the zip's directory; a file input, by its name.
 */
final class InputContent {

    /** What the renter reads when a zip can't be read. */
    static final String UNREADABLE_ZIP =
            "We couldn't read that zip. Check that it opens on your computer, then upload it again.";

    private InputContent() {}

    /** The words for the first rule the paths don't meet; empty when they all do, or there are no rules. */
    static Optional<String> problem(Template.Input input, List<String> paths) {
        return input.mustContain().stream()
                .filter(rule -> paths.stream().noneMatch(rule::matches))
                .map(Template.ContentRule::message)
                .findFirst();
    }

    /** A folder input given a single zip is checked by what the zip holds. */
    static boolean isZip(Template.Input input, List<String> paths) {
        return input.kind() == Template.InputKind.FOLDER
                && paths.size() == 1
                && paths.get(0).toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }
}
