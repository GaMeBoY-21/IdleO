/**
 * The flags module (ADR 0056): the kill switches and maintenance mode, each a {@link com.idleo.controlplane.Flag}, read by
 * every module through the shared kernel's {@link com.idleo.controlplane.Flags}, which this module implements. The
 * database ({@code flags.flags}) is the truth; each instance caches it, drops the cache when Valkey says a flag changed,
 * and reads it again every {@code flags.refresh-every} (30 s) anyway. Flags that can't be read are their safe values.
 *
 * <ul>
 *   <li>{@code GET /admin/v1/flags}; {@code POST /admin/v1/flags/{id}/pause} (at once, with a factor and a reason, and
 *       optionally until a time it runs again by itself) and {@code /resume} (approved by another admin when there is
 *       one, and never waiting out a delay when there isn't). Every change is audited; a flag that says so emails the
 *       Owners.
 *   <li>{@code GET /v1/public/status}, with no sign-in: what's paused and maintenance's expected end, for the app's
 *       banner and the website's status line.
 *   <li>Maintenance mode: every {@code /v1} write but the {@link com.idleo.controlplane.OpenDuringMaintenance} handlers
 *       is 503 with Retry-After.
 * </ul>
 *
 * <p>Nothing depends on this module: every module reads flags through the kernel.
 */
@ApplicationModule(allowedDependencies = {"identity"})
package com.idleo.controlplane.flags;

import org.springframework.modulith.ApplicationModule;
