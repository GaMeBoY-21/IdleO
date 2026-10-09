package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.GpuClasses;
import com.idleo.controlplane.sessions.SessionRequests;
import com.idleo.controlplane.sessions.Template;
import java.util.List;

/**
 * The GPU memory a session needs (ADR 0022): the template's minimum, or, when the template sizes it from the renter's
 * model ({@link Template.VramFromInput}) and the model is an upload, {@code base_mb + per_gb_mb} for each GiB of it,
 * whichever is larger, rounded up to a {@link GpuClasses} class. A link's size isn't known, so a model given as a link
 * needs the template's minimum, and the renter may ask for more.
 */
final class SessionVram {

    private SessionVram() {}

    static int needed(Template template, List<SessionRequests.Input> inputs) {
        var rule = template.vramFromInput();
        if (rule == null) {
            return template.minVramMb();
        }
        return inputs.stream()
                .filter(input -> input.name().equals(rule.input()) && input.uploadId() != null)
                .findFirst()
                .map(model -> GpuClasses.roundUp(Math.max(template.minVramMb(), fromSize(rule, model.uploadBytes()))))
                .orElse(template.minVramMb());
    }

    /** {@code base_mb} plus {@code per_gb_mb} for each GiB, part GiBs counted in proportion and rounded up. */
    static int fromSize(Template.VramFromInput rule, long bytes) {
        long grows = Math.ceilDiv(rule.perGbMb() * bytes, CatalogInputs.GIB);
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, rule.baseMb() + grows));
    }
}
