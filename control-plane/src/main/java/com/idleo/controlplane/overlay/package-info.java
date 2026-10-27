/**
 * The overlay module. Owns: Overlay device approvals and per-session access rules; the only module that talks to the overlay coordinator.
 *
 * <p>Its tables live in the {@code overlay} database schema, and no other module reads them.
 */
@ApplicationModule
package com.idleo.controlplane.overlay;

import org.springframework.modulith.ApplicationModule;
