/**
 * The identity module. Owns: users, their linked sign-in identities, account status, and later KYC
 * status. Its tables live in the {@code identity} database schema, and no other module reads them.
 *
 * <p>Sign-in happens at the identity provider (Google or email link, ADR 0009). The control plane
 * verifies the provider's ID token on every request and maps it to our own user, creating the user
 * on first use. The provider's issuer and subject stay inside this module: every other module, and
 * every API response, sees only our UUIDv7 user id, through {@link
 * com.idleo.controlplane.identity.CurrentUser}.
 *
 * <p>Endpoints. Every error is an RFC 9457 problem detail.
 *
 * <ul>
 *   <li>{@code GET /v1/me}: the signed-in user's id, email, display name, status and creation time,
 *       with an {@code ETag}.
 *   <li>{@code PATCH /v1/me}: changes {@code displayName} only. It needs {@code If-Match} with the
 *       current {@code ETag}: 428 without it, 412 when it's stale, 400 with field details when the
 *       body is invalid.
 * </ul>
 *
 * <p>Every request also gets: 401 with {@code WWW-Authenticate: Bearer} for a missing or rejected
 * token; 403 for a suspended account; 403 for a first sign-in whose email isn't verified.
 */
@ApplicationModule
package com.idleo.controlplane.identity;

import org.springframework.modulith.ApplicationModule;
