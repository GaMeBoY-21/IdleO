/**
 * The trust module. Owns: Ratings, disputes, canary jobs, abuse flags and bans.
 *
 * <p>Its tables live in the {@code trust} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.trust;

import org.springframework.modulith.ApplicationModule;
