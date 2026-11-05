/**
 * The queues module (ADR 0053). Owns the console's one inbox: each module says what's waiting through its {@link
 * com.idleo.controlplane.WorkQueue} beans, and this keeps who's on each item, its timers against the Owner's targets and
 * its priority, in the {@code queues} schema. It also owns macros and bulk actions. Decisions stay on each kind's own
 * screen, in the module that owns it, and nothing depends on this one.
 */
@ApplicationModule(allowedDependencies = {"identity"})
package com.idleo.controlplane.queues;

import org.springframework.modulith.ApplicationModule;
