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
 * <p>API keys (ADR 0025) are the second credential, for the CLI, the SDK and scripts. A bearer value starting {@code
 * idleo_} is checked as a key, anything else as an ID token, in one place ({@code IdentitySecurityConfiguration}). A
 * key is shown once and stored as a SHA-256 hash; {@code read} keys make GET requests only; and no key may create a
 * key, approve a CLI sign-in, add, remove or price a machine, issue its enrolment code, or change notification
 * choices ({@code browser-sign-in-required}). The CLI gets its key by device sign-in, shaped like RFC 8628.
 *
 * <ul>
 *   <li>{@code GET /v1/api-keys}, {@code POST /v1/api-keys} (Idempotency-Key; a repeat is 409 {@code
 *       secret-shown-once}; at most 20 keys), {@code DELETE /v1/api-keys/{id}}.
 *   <li>{@code POST /v1/cli-logins} (no sign-in), {@code GET /v1/cli-logins?code=} and {@code POST
 *       /v1/cli-logins/{id}/approve} (a browser's sign-in), {@code POST /v1/cli-logins/{id}/token} (no sign-in; the
 *       poll token): 400 {@code authorization-pending} until approved, then the key once, then 400 {@code
 *       cli-login-expired}.
 * </ul>
 *
 * <p>Every request also gets: 401 with {@code WWW-Authenticate: Bearer} for a missing or rejected
 * token; 403 for a suspended account; 403 for a first sign-in whose email isn't verified.
 *
 * <p>The admin console (ADR 0027) lives under {@code /admin/v1}, behind its own security chain: an ID token (never
 * an API key) of a user with the {@code admin} role, which only the {@code grant-admin} and {@code revoke-admin}
 * operator commands change, and an admin session from a TOTP code (RFC 6238) or a one-time recovery code, in the
 * {@code Admin-Session} header. Anything less is the 404 of a path that doesn't exist. The role alone reaches only
 * {@code GET}, {@code POST} and {@code DELETE /admin/v1/session} and enrolment ({@code POST /admin/v1/second-factor},
 * then {@code /confirm}). The fifth wrong code in 15 minutes locks admin access until the role is revoked and granted
 * again.
 *
 * <p>Every privileged action, in any module, is recorded through {@link
 * com.idleo.controlplane.identity.AuditTrail} in the action's own transaction, in the append-only {@code
 * identity.audit_log}, read at {@code GET /admin/v1/audit}. {@code GET /admin/v1/dashboard} shows each module's
 * {@link com.idleo.controlplane.identity.DashboardFigures}; {@code /admin/v1/users} finds, suspends and reinstates
 * users, and {@code /admin/v1/invites} makes invite codes (shown once) and lists who used them.
 */
@ApplicationModule
package com.idleo.controlplane.identity;

import org.springframework.modulith.ApplicationModule;
