package com.idleo.controlplane.sessions;

import java.util.List;

/**
 * Something a renter can run (product surface 3.0, ADR 0021): a group of templates, shown as one tile on Home and one
 * choice in Book. A template is one way to run a service. Listed in the template catalog, above the templates.
 *
 * @param tagline a few words under the name ("a model on your data")
 * @param runningLabel what a running session of it is doing, for its live screen ("Fine-tuning")
 * @param icon one of {@link #ICONS}, drawn by each client
 * @param hidden kept off Home and Book's list, though its templates can still be booked (diagnostics)
 */
public record Service(String id, String name, String tagline, String runningLabel, String icon, boolean hidden) {

    /** The icons a service may name; each client draws its own. */
    public static final List<String> ICONS =
            List.of("layers", "code", "image", "mic", "table", "cube", "notebook", "plug", "gamepad", "flask", "pulse");
}
