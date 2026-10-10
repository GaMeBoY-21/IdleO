package com.idleo.controlplane.hosts;

import java.util.UUID;

/** A host was removed by its owner: its certificates are revoked, and its agent stream must close (ADR 0023). */
public record HostRemoved(UUID hostId) {}
