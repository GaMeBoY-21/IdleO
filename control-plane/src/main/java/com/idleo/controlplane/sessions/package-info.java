/**
 * The sessions module. Owns: The session lifecycle, leases, attempts, checkpoints and per-session credentials.
 *
 * <p>Its tables live in the {@code sessions} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.sessions;

import org.springframework.modulith.ApplicationModule;
