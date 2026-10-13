package com.idleo.controlplane.hosts;

import java.util.UUID;

/**
 * An admin revoked every certificate of a host (ADR 0027): its agent stream must close, and it can't reconnect until
 * its owner enrols it again with a new code.
 */
public record HostCertificatesRevoked(UUID hostId) {}
