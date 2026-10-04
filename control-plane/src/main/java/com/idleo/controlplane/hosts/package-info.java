/**
 * The hosts module. Owns: Hosts, GPUs, inventory, availability, price listings and the host score.
 *
 * <p>Its tables live in the {@code hosts} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.hosts;

import org.springframework.modulith.ApplicationModule;
