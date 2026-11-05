/**
 * The console module (ADR 0052). Owns finding things in the admin console: the one search box, which asks every module's
 * {@link com.idleo.controlplane.AdminSearch} beans in parallel, each within a time limit, and answers only with the kinds
 * of thing the admin may read.
 *
 * <p>It reads no other module's tables: what each finds comes through its bean. Nothing depends on it.
 */
@ApplicationModule
package com.idleo.controlplane.console;

import org.springframework.modulith.ApplicationModule;
