/**
 * The notifications module. Owns: Email, SMS and push messages, and what each person chose to be told
 * about.
 *
 * <p>Its tables live in the {@code notifications} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 *
 * <p>Nothing sends a message yet; what exists is the choices (ADR 0024). Every kind in {@code
 * docs/plan/product-surface.md} section 9 has one switch for email and one for the app, all on by default,
 * except the kinds about money and safety, which can't be turned off. A kind someone left on is on for
 * the senders that come later.
 *
 * <ul>
 *   <li>{@code GET /v1/me/notifications}: every kind with its title, its two switches, and whether it is
 *       locked on and why.
 *   <li>{@code PUT /v1/me/notifications}: replaces the choices; a kind left out is on again. 422 {@code
 *       notification-locked} when a locked kind is switched off; 400 {@code validation-failed} for an
 *       unknown or repeated kind.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.notifications;

import org.springframework.modulith.ApplicationModule;
