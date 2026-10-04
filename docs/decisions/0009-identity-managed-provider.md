# 0009. Identity through a managed provider, built so we can leave it

- **Status:** Accepted
- **Date:** 2026-10-05

Sources:
- docs/plan/technical-design.md lines 63, 95, 536, 552, 751, 764
- docs/plan/execution-plan.md lines 202, 351
- ADR 0008
- docs/prompts/identity-sign-in.md

## Context

Every user-facing feature (hosts, sessions, the web app, payments) hangs off a signed-in user. Nikki chose a managed identity provider now, with the option to move to a self-hosted one (Keycloak or ZITADEL) later. That move must be a config change plus a user re-link, never a rewrite.

## Decision

### Provider

**Google Identity Platform**, the paid tier of Firebase Auth.
- **Sign-in methods:** Google, and passwordless email link.
- **No passwords at all.** Password hashes are what make leaving a provider hard. Without them there's nothing to migrate.
- **Phone:** verified once, when a host sets up payouts (a later piece of work), never used for every login. India SMS costs $0.07 per message on Identity Platform.

### Why managed, and why this one

Compared with AWS Cognito Essentials, checked on 2026-10-05:

| | Identity Platform | Cognito Essentials |
| --- | --- | --- |
| Free monthly active users | 50,000 | 10,000 |
| Price above the free tier | $0.0055 per MAU up to 100,000 | $0.015 per MAU |
| Cost at 100,000 MAU | About $275 a month | About $1,350 a month |
| Phone OTP in India | Works without our own DLT registration | — |

Sources: [Identity Platform pricing](https://cloud.google.com/identity-platform/pricing) and [Amazon Cognito pricing](https://aws.amazon.com/cognito/pricing/).

A self-hosted provider would mean running and securing one more critical service from day one, for a one-person team.

### Portability rules, enforced in code

1. **The backend speaks only standard OIDC.**
   - There's no Firebase or Google SDK in `control-plane/`, and an enforcer rule bans `com.google.firebase` dependencies.
   - Tokens are checked by Spring Security's OAuth2 resource server:
     - the RS256 signature, against the issuer's published keys, found through OIDC discovery
     - `iss`, `aud`, `exp` (required), `iat` (required, not in the future beyond a 60-second skew), a non-empty `sub`, and `auth_time` (if present, not in the future)
   - For Identity Platform:
     - The issuer is `https://securetoken.google.com/<project-id>`, and the audience is the project id. Google: "`aud` must be your Firebase project ID; `iss` must be `https://securetoken.google.com/<projectId>`; `sub` must be a non-empty string".
     - The keys are published as X.509 certificates at `https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com` ([Verify ID tokens using a third-party JWT library](https://firebase.google.com/docs/auth/admin/verify-id-tokens#verify_id_tokens_using_a_third-party_jwt_library)).
     - The same keys are published as a JWK set at `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`. That's the `jwks_uri` in the issuer's discovery document, `https://securetoken.google.com/<project-id>/.well-known/openid-configuration`, which the control plane uses.
2. **Trusted issuers are a list in config, each with its own audience** (`identity.issuers[n].issuer-uri` and `.audience`, from the environment).
   - There's one decoder per issuer, each with its own audience validator.
   - Spring's `JwtIssuerAuthenticationManagerResolver.fromTrustedIssuers(...)` alone checks the issuer but not the audience, so a token minted for another project on the same issuer would pass.
3. **Our user id is the only id.**
   - The provider's `sub` and issuer live in exactly one table, `identity.external_identities`, and never leave the identity module.
   - Every other module and every API response uses our UUIDv7 user id.
   - An ArchUnit test fails if any class outside the identity module touches the identity module's internals or the raw token.
4. **Account state is ours, not the provider's.** Status (active or suspended), display name, and later roles, phone and KYC live in our database. The provider's custom claims are never used for authorization.

### Account rules

- **The email must be verified.** A first sign-in needs `email_verified = true`, or no account is created and the request gets a 403.
- **No automatic linking.** A new (issuer, subject) is never linked to an existing user by email, because that's how accounts get taken over. Linking happens only in the controlled migration below.
- **Suspension is checked on every request** against our database, so it takes effect on the next call.

### Persistence: Spring Data JDBC, no JPA

The module uses explicit SQL with no lazy loading, which suits aggregates owned by one module. The booking transaction later needs `SELECT … FOR UPDATE SKIP LOCKED` through `JdbcClient` anyway. Optimistic locking uses Spring Data JDBC's `@Version`. Flyway still owns all DDL.

### Ids and time

- **Ids:** created in the application before insert, as UUIDv7, by one generator: `com.fasterxml.uuid:java-uuid-generator` 5.2.0.
  - It's the widely used Java UUID library, and implements RFC 9562 version 7.
  - It takes its time source from our injected `Clock`.
- **Time:** every "now" comes from the one `Clock` bean.

## The exit plan: moving to a self-hosted provider

1. **Run the new provider** (Keycloak or ZITADEL), with Google and email-link sign-in configured the same way.
2. **Add it as a second trusted issuer** (`identity.issuers[1]`). Both are now accepted, each with its own audience. The code already supports this, and it's tested.
3. **Each user signs in once with the new provider and confirms their existing account** by a verified email code sent by us, not by either provider. A second `identity.external_identities` row then links the new (issuer, subject) to the same user. This confirmation flow is built when the move happens; until then nothing links identities.
4. **After a cutover date, remove the old issuer** from config. Unlinked old identities stop working; their users sign in with the new provider and confirm as in step 3.

No user loses data, because the user row, its id and everything other modules hold are untouched. No other module changes, because none of them ever saw the provider's ids.

## Alternatives considered

- **AWS Cognito Essentials:** rejected on cost and free tier, as in the table above.
- **Self-hosting Keycloak or ZITADEL now:** rejected for now. That's one more critical service to run and secure, and the exit plan keeps the door open.
- **Passwords:** rejected. Password hashes are what make leaving a provider hard.
- **The Firebase Admin SDK in the backend:** rejected. It would tie the backend to one provider; standard OIDC verification needs no SDK.
- **JPA:** rejected, for the reasons under Persistence.

## Consequences

- Sign-in itself happens at the provider; the control plane only verifies ID tokens.
- The web app uses the provider's SDK to sign in and refresh the one-hour ID token.
- A suspended user is locked out on their next request, whatever their token says.
- Leaving the provider means running the exit plan above. It costs one confirmation per user, and no code outside the identity module.
