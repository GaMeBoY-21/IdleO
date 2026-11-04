# 0051. Admin access and control: roles of fine-grained permissions

- **Status:** Accepted
- **Date:** 2026-11-04

Sources:
- ADR 0027 (the console, its second factor, sessions and audit log), which this extends
- ADRs 0037 (personal data), 0039 and 0040 (money in and out)
- docs/runbooks/admin.md

## Context

ADR 0027 gave one person a console with one yes-or-no role. Anyone who holds it can do everything: read every person's data, move money, lift any ban. Before a second person is given access, what each admin may do has to be something the server decides per endpoint, and something a reviewer can read.

The console had 91 endpoints when this was written (`control-plane/src/test/resources/admin-permission-matrix.csv` lists them).

## Decision

### Permissions and roles

**A permission is one verb** (`AdminPermission`), derived from what the endpoints do. Each endpoint declares one with **one annotation**, `@RequiresPermission`. **`AdminEndpointCheck` refuses to start** the control plane if an `/admin/` endpoint has none, so nothing is open by default. `AdminAccessInterceptor` is the one check. It works on the handler rather than the path, so a percent-encoded spelling meets the same rule.

An admin without the permission gets **403 `admin-permission-required`**, naming it. Anyone who isn't an admin still gets the 404 of a path that doesn't exist (ADR 0027): the security chain answers them before the check runs.

**A role is a named bundle, in the database** (`identity.admin_roles`, `admin_role_permissions`, `admin_role_grants`): Owner (everything), Operations, Support, Trust & Safety, Finance and Auditor. An admin can hold several, or none: an admin with no role can sign in and see nothing else. `self` is the one permission held by every admin and in no role: their own session and second factor.

**The first Owner** comes from the operator command, `grant-admin <email> --reason "<why>" --role owner`. Everyone who was already an admin became an Owner in the migration.

**The console shows only what the admin may do:** sections they can't read are gone from the navigation, an address typed by hand says which permission is missing, and an action they can't take is disabled with a tooltip naming it. The server stays the only judge.

**`admin-permission-matrix.csv` is the committed table** of every endpoint times every role. `AdminPermissionMatrixTests` works it out from the declarations and the database and compares, so a change in who can do what shows up in review. It also makes the real request for every cell (91 endpoints times 6 roles, 546), with a made-up record and an empty body, so an allowed POST stops at validation and nothing happens.


`self` covers `GET`, `POST` and `DELETE /admin/v1/session`, and `POST /admin/v1/second-factor` and `/second-factor/confirm`.

**Permissions that weren't clear, and what was chosen:**
- **`payouts.hold` doesn't exist.** No endpoint holds a payout on its own: a hold is what suspending a user with the cause `host_misconduct` does, so it's `users.suspend`.
- **`POST /payouts/retry` is `payouts.release`:** it's the endpoint that pushes one person's money out now. `POST /payouts/runs` is `payouts.run`.
- **`POST /users/{id}/exports` is `privacy.export`, not `export.create`:** the export goes to the person, never to the admin who starts it. `export.create` is for files the admin receives.
- **`GET /sessions/{id}/log` is `sessions.log.read`, and the Auditor doesn't have it.** A session's log is a renter's own output, read with a reason, to judge a report. It isn't part of a review of what admins did.
- **`GET /tax` has no endpoint of its own:** the Tax screen is the export, so its section needs `export.create`.
- **There are no kill switches yet,** so no permission for them. When one is built, its permission is sensitive.

### Signing in: passkeys, step-up, session limits

**Passkeys are the primary factor,** through Spring Security's own WebAuthn support (7.1, \`spring-security-webauthn\`): \`Webauthn4JRelyingPartyOperations\` checks every registration and assertion. User verification is required, the relying party's id comes from \`identity.admin.passkeys.rp-id\` (the prod profile refuses \`localhost\`), the allowed origins are the web app's, and an admin can have several, each named. Its login filters and HTTP session aren't used, because the admin chain is stateless: \`AdminPasskeys\` keeps challenges in the database, used once, for five minutes, so any instance can finish what another began. A wrong assertion counts towards the same lockout as a wrong code. **TOTP and recovery codes stay as the fallback,** and first enrolment is still TOTP, so nobody can be locked out by losing a device.

**Step-up.** A permission marked sensitive in \`AdminPermission\` needs a factor given in the last 5 minutes (\`identity.admin.step-up-window\`). They are: revealing personal data, every money action, role changes, exports, lifting a ban or suspension, and unblocking an image; kill switches when they exist. Signing in counts. Otherwise the server answers 403 \`step-up-required\`; the console asks for a passkey or a code in place (\`POST /admin/v1/session/step-up\`) and sends the very same request again with the same idempotency key. Changing one's own passkeys asks too (\`stepUp = true\`).

**Sessions** keep the 30-minute idle renewal and the 8-hour limit from ADR 0027. Each now records its start, last use, address and browser. An admin sees their own and can end any; the Owner (\`admins.manage\`) sees everyone's and can end any. **A sign-in from a browser or address that admin hasn't used emails them,** with the address masked, through the \`AdminNoticed\` event, in the transaction that started the session.

### Personal data: masked by default, revealed with a reason

**One masking function, applied to every admin response.** \`PersonalDataMask\`: an email \`ni•••@gmail.com\`, a phone its last 2 digits, a PAN and bank details their last 4, a UPI ID \`ni•••@okhdfc\`, an IP its /24 or /48, and a postal address nothing. \`AdminResponseMasking\` (one \`ResponseBodyAdvice\`) runs it over the JSON of every response of an endpoint with \`@RequiresPermission\`. So masking is the rule, not something each endpoint remembers. It also reads free text: a ticket's message or a rating's comment with an email in it is masked too. Names aren't masked: an admin has to compare the name on a PAN with the bank's.

**The exceptions are few and marked** (\`unmasked = true\`): the admin's own second factor (the secret and the link for the QR code) and the one reveal. Files aren't JSON and go as they are: the TDS export and an invoice's PDF, each under its own permission.

**The test.** \`AdminMaskingTests\` makes a person with known data in every table (\`People\`), adds a phone, a PAN, a UPI ID, an address and an IP in the places admins read them, and reads every admin GET endpoint (45, found from the mappings, so a new one joins by itself). It fails if any value appears whole, or if an endpoint can't be reached.

**Reveal.** \`POST /admin/v1/personal-data/reveal\` needs \`users.pii.reveal\`, a recent factor and a reason (at least 10 characters, or a ticket's number), and returns one field. Each module names what it can show (\`RevealableFields\`): \`email\`, \`pan\`, \`upi\`, \`reply_to\`, \`security_event_ip\` and \`audit_ip\`. It replaces the PAN's own reveal endpoint: one implementation. The console keeps the value only in the component that shows it.

**The access log** (\`identity.personal_data_access\`): who, whose data, which field, why, when and from which session. Opening a person's page writes an entry too, with the field \`view\`. It's kept a year, like the security log, and read by whoever holds \`audit.read\` (the Owner and the Auditor). A person's "Your data" export shows them who at IdleO viewed or revealed their data, by the admin's id. **LEGAL(confirm):** whether the export must name the person, and that the period is right. A reveal is also an audit row.

### The audit log: tamper-evident, off-site, searchable

**A hash chain, made by the database.** A \`BEFORE INSERT\` trigger on \`identity.audit_log\` gives each row its \`seq\`, its \`prev_hash\` (the row before's \`hash\`) and its \`hash\`, the SHA-256 of \`prev_hash\` and the row's canonical content (\`identity.audit_row_content\`: every field in one fixed order, as a JSON array). So every writer is chained, of this release or the one before it during a canary, and none can skip it or choose its place. Writers wait for the lock on the one-row \`identity.audit_chain_head\`, held until they commit, so the chain's order is the order they commit in. Its trigger runs as the table's owner: the application may read the head but not change it. The rows from before this were chained by the migration, oldest first, from 32 zero bytes.

**Checking it.** \`AuditChain.verify\` walks the whole table in one SQL statement: each hash is what its content makes, each row follows the one before with no number missing, and the head is the last row. It runs daily under \`OneAtATime\` and from a button on the Audit log screen, and each result is kept in \`identity.audit_chain_checks\`. A break pages (\`AuditChainBroken\`, the one alert here that does), and a check that hasn't run for 26 hours tells the digest.

**Each row also says where it came from:** the request id (also the \`X-Request-Id\` of the admin's response), the admin session, the address, the browser, and the approval it ran under. They're columns of their own, set from the request, so they don't break ADR 0037's rule that an entry's words hold no address.

**The off-site copy.** \`AuditOffsiteCopies\` writes each finished India day's rows and the chain head after them as two files, in chain order, so the files join with no gaps. A file is written once with \`If-None-Match: *\` and never replaced; finding other bytes under the key is an error, not an overwrite. Missed days catch up in order, and a day with no rows still gets its head. In S5 the store is an R2 bucket with a lock on the prefix for 8 years (\`ops/backup/r2-audit-lock.json\`, docs/runbooks/audit-chain.md). R2 has no object versions, so write-once plus the lock is what keeps each version. The prod profile refuses to start without the store. A day with no copy tells the digest (\`AuditCopyMissing\`).

**Search and export.** \`GET /admin/v1/audit\` also filters by approval and request id. \`POST /admin/v1/audit/export\` gives the same search as CSV, up to 50,000 rows, with addresses masked, under \`export.create\`, and is itself logged. Cells can't be read as spreadsheet formulas.

**Still append-only for the application:** a test reads the database's privileges for \`idleo_application\`: no \`UPDATE\`, \`DELETE\` or \`TRUNCATE\` on the log, and no \`UPDATE\` on the head.

## Alternatives considered

- **Spring Security's WebAuthn login filter and its session-based repositories.** They assume an HTTP session and a form of login that ends in one. Our admin chain is stateless, beside an ID token and an \`Admin-Session\` header. Its relying party operations are the hard part, and those are what we use.
- **WebAuthn4J directly.** The pinned Spring Security has its own support, built on it, so one more direct dependency would add nothing.
- **Masking in each response type, with an annotation on each field.** A forgotten annotation shows the data, and free text can't be annotated at all. Working on the response's JSON means nothing can be forgotten. The price is a pattern that could mask something that only looks like an email or a phone, so \`PersonalDataMaskTests\` holds what must stay alone (ids, times, Razorpay's ids, image digests), and a few machine-value fields are skipped by name.
- **Chaining rows afterwards, with a job.** No lock on the write path, but a window in which a row can change unnoticed, and the \`hash\` couldn't live on the row of an append-only table.
- **Working out the hash in Java.** An old release writing during a canary wouldn't chain, and any other writer could skip it.

- **Roles as code (an enum of bundles).** Every change of a bundle would be a release, but the table says roles are data people may want to change, and a migration is just as reviewable.
- **Spring Security's `@PreAuthorize`.** It reads as an expression, can't be checked at startup without parsing it, and would need method security on package-private controllers. One annotation with an enum can't be misspelt.
- **A permission per path pattern, in one central table.** A new endpoint would be open until someone remembers the table. The annotation sits on the handler and the startup check makes it mandatory.

## Consequences

- A new admin endpoint needs a permission, and a new permission needs a migration adding it to its roles and the Owner (the matrix test fails otherwise).
- Every admin request reads the admin's roles from the database: one small query, so a change counts from the next request.
- `grant-admin` still needs nobody's approval. It's the operator's break-glass with access to the server, and it's audited.
