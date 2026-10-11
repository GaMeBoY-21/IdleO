/**
 * The identity module. Owns: users, their linked sign-in identities, account status, and later KYC
 * status. Its tables live in the {@code identity} database schema, and no other module reads them.
 *
 * <p>Sign-in happens at the identity provider (Google or email link, ADR 0009). The control plane
 * verifies the provider's ID token on every request and maps it to our own user, creating the user
 * on first use. The provider's issuer and subject stay inside this module: every other module, and
 * every API response, sees only our UUIDv7 user id, through {@link
 * com.idleo.controlplane.identity.CurrentUser}. Other modules ask whether a user exists through {@link
 * com.idleo.controlplane.identity.UserDirectory}.
 *
 * <p>Access is by invitation while {@code identity.invite-only} is true (ADR 0024): a new user is {@code waiting}
 * until they enter an invite code, and may meanwhile only call the three endpoints below; every other call is a 403
 * {@code invite-required}. Codes are made by the {@code create-invites} operator command, stored only as a hash,
 * used once, and a user may try five an hour.
 *
 * <p>Endpoints. Every error is an RFC 9457 problem detail.
 *
 * <ul>
 *   <li>{@code GET /v1/me}: the signed-in user's id, email, display name, status ({@code waiting}, {@code active}
 *       or {@code suspended}), how they signed in ({@code signInMethods}: {@code google} or {@code email-link}) and
 *       creation time, with an {@code ETag}.
 *   <li>{@code PATCH /v1/me}: changes {@code displayName} only, 1 to 60 characters. It needs {@code If-Match} with
 *       the current {@code ETag}: 428 without it, 412 when it's stale, 400 with field details when the body is
 *       invalid.
 *   <li>{@code POST /v1/me/activate}: spends an invite code. 400 {@code invite-invalid} for a wrong, used or
 *       expired code, without saying which; 429 {@code too-many-attempts} after five tries in an hour.
 * </ul>
 *
 * <p>Every request also gets: 401 with {@code WWW-Authenticate: Bearer} for a missing or rejected
 * token; 403 for a suspended account; 403 for a first sign-in whose email isn't verified.
 */
@ApplicationModule
package com.idleo.controlplane.identity;

import org.springframework.modulith.ApplicationModule;
