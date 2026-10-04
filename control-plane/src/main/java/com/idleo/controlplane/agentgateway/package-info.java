/**
 * The agent gateway module. Owns: the agents' gRPC transport, and later their streams, presence,
 * durable agent commands and routing to the right instance.
 *
 * <p>So far it serves {@code EnrollmentService.Enroll} on the control plane's one gRPC TLS port
 * (ADR 0010) and hands each request to the hosts module's {@link
 * com.idleo.controlplane.hosts.HostEnrolment}; it never touches codes, keys or certificate records
 * itself.
 */
@ApplicationModule
package com.idleo.controlplane.agentgateway;

import org.springframework.modulith.ApplicationModule;
