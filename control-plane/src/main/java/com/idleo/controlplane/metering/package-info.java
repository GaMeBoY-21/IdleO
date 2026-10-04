/**
 * The metering module. Owns: Usage minutes and the two-witness rule.
 *
 * <p>Its tables live in the {@code metering} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.metering;

import org.springframework.modulith.ApplicationModule;
