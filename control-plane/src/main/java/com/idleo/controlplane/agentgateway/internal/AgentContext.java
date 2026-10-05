package com.idleo.controlplane.agentgateway.internal;

import io.grpc.Context;
import java.util.UUID;

/**
 * Who is calling, as established by {@link AgentCertificateInterceptor} from the client certificate.
 * Nothing in a message ever identifies the caller (ADR 0007).
 */
final class AgentContext {

    static final Context.Key<UUID> HOST_ID = Context.key("idleo-host-id");
    static final Context.Key<byte[]> CERTIFICATE_FINGERPRINT = Context.key("idleo-certificate-fingerprint");

    private AgentContext() {}
}
