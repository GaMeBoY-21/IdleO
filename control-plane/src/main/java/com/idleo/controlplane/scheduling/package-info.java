/**
 * The scheduling module. Owns: Matching, reservations and offers.
 *
 * <p>Its tables live in the {@code scheduling} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.scheduling;

import org.springframework.modulith.ApplicationModule;
