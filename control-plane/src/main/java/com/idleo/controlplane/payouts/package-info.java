/**
 * The payouts module. Owns: Escrow and weekly payouts.
 *
 * <p>Its tables live in the {@code payouts} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.payouts;

import org.springframework.modulith.ApplicationModule;
