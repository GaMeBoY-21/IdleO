/**
 * The agentgateway module. Owns: The agents' gRPC streams, presence, durable agent commands and routing to the right instance.
 *
 * <p>Its tables live in the {@code agentgateway} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.agentgateway;

import org.springframework.modulith.ApplicationModule;
