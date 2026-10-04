/**
 * The identity module. Owns: Users, login, KYC status and tokens.
 *
 * <p>Its tables live in the {@code identity} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.identity;

import org.springframework.modulith.ApplicationModule;
