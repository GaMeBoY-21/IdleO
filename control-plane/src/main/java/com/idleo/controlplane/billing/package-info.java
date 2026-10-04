/**
 * The billing module. Owns: Wallets, ledger settlements, payment intents and gateway webhooks.
 *
 * <p>Its tables live in the {@code billing} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 */
@ApplicationModule
package com.idleo.controlplane.billing;

import org.springframework.modulith.ApplicationModule;
