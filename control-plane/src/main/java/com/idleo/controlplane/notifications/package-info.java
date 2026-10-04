/**
 * The notifications module. Owns: Email, SMS and push messages.
 *
 * <p>Its tables live in the {@code notifications} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.notifications;

import org.springframework.modulith.ApplicationModule;
