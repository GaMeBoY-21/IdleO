package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.Template;

/**
 * The disk a session needs on its host (ADR 0022): every uploaded input staged there, plus the template's scratch
 * disk, with a 10% margin, in MiB. The scheduler offers a session only to a host that reported at least this much
 * free.
 */
final class SessionDisk {

    private static final long MIB = 1024L * 1024;

    private SessionDisk() {}

    static int neededMb(Template template, long inputBytes) {
        long mb = Math.ceilDiv(inputBytes, MIB) + template.scratchMb();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.ceilDiv(mb * 11, 10)));
    }
}
