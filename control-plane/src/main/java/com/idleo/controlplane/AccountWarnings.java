package com.idleo.controlplane;

import java.util.UUID;

/** The ladder's first step, for a bulk action (ADR 0053): anything past a warning is never bulk. */
public interface AccountWarnings {

    /** Warns the person from this template, and tells them. Audited as this admin. */
    void warn(UUID person, String template, String reason, UUID admin);

    /** Whether there's such a template, and it's a warning. */
    boolean warning(String template);
}
