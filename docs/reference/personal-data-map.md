# Personal data map

Every place IdleO keeps something about a person: what it is, why we keep it, how long, and what happens to it when the person deletes their account. Every change that stores something new about a person updates this page in the same commit. `PersonalDataMapTests` fails when a database table or column, or a storage key prefix, exists that this page doesn't list, and when a retention rule named here has no job (or a job no line here).

How to read it:

- **Personal** columns are in bold: they name, describe or can reach a person (an email, a name, a file name, free text a person or an admin wrote, an address, a hardware id). A plain id (a UUIDv7) is pseudonymous: it points at a person only through `identity.users`, which erasure empties.
- **Kept** names the retention rule that deletes it (`rule: name`, one job each, see "Retention rules" at the end), or says what keeps it.
- **On erasure** is what the erasure job (ADR 0037) does: *deleted*, *cleared* (the personal columns set to null), *kept* (no personal data in it), or *de-identified* (the row stays, keyed by the opaque user id, with every personal column cleared).
- Periods marked LEGAL(confirm) are a lawyer's to confirm; they are set in one place, the `legal:` block of `control-plane/src/main/resources/application.yaml`.

## The database (PostgreSQL)

One schema per module. Backups hold the same data for 30 days (see "Backups").

### identity

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `identity.users` | id, status, **email**, email_verified, **display_name**, created_at, updated_at, version | The account: who to write to and what to call them | The account's life | Cleared, status `erased`; the row stays as a tombstone so ledger ids still resolve to "an erased account" |
| `identity.external_identities` | id, user_id, **issuer**, **subject**, created_at, last_sign_in_at | Which sign-in provider account is this person | The account's life | Deleted |
| `identity.api_keys` | id, user_id, **name**, scope, key_hash, display_prefix, created_via, created_at, last_used_at, revoked_at | The CLI's and scripts' keys (ADR 0025); the name is the person's label | The account's life (revoked keys too, so a leaked one stays recognisable) | Revoked first, then deleted |
| `identity.cli_logins` | id, user_code_hash, poll_token_hash, **device_name**, created_at, expires_at, approved_by, approved_at, redeemed_at, api_key_id | A device sign-in in progress (10 minutes) | `rule: cli-logins` | Deleted |
| `identity.invite_codes` | id, code_hash, **note**, created_by, created_at, expires_at, used_by, used_at | Access by invitation (ADR 0024); the note is the admin's label for whom | Unused: `rule: invite-codes`; used: the account's life | The note of the code they used is cleared; codes they made as an admin stay, by id |
| `identity.activation_attempts` | id, user_id, attempted_at | 5 invite tries an hour | `rule: activation-attempts` | Deleted |
| `identity.admins` | user_id, granted_by, granted_at, totp_secret, totp_confirmed_at, last_totp_step, locked_at | Who may use the admin console (ADR 0027); the TOTP secret is sealed | While an admin | Deleted (the role is revoked first) |
| `identity.admin_sessions` | id, user_id, identity_id, token_hash, created_at, expires_at, ends_by, ended_at | A live admin console session | `rule: admin-sessions` | Deleted |
| `identity.admin_recovery_codes` | id, user_id, code_hash, created_at, used_at | The admin's one-time codes, hashed | While an admin | Deleted |
| `identity.admin_code_attempts` | id, user_id, succeeded, attempted_at | Locks the console after 5 wrong codes in 15 minutes | `rule: admin-code-attempts` | Deleted |
| `identity.idempotency_keys` | user_id, idempotency_key, request_hash, state, response_status, response_location, **response_body**, locked_at, created_at, expires_at | Repeating a create request returns the same answer | `rule: idempotency-keys` | Deleted |
| `identity.audit_log` | id, actor_kind, actor_user_id, actor_name, action, target_type, target_id, **reason**, before, after, created_at | Every privileged action (ADR 0027), append-only by trigger. It holds ids only: no IP, email or name (`AuditLog` refuses an entry with an email or an address in it; a test reads every row). The reason is an admin's free text | `legal.retention.audit-log`, 8 years, LEGAL(confirm); not deleted yet (ADR 0037) | Kept: ids only. The erasure itself is audited by id |

### hosts

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `hosts.hosts` | id, owner_user_id, **name**, status, created_at, updated_at, version, os, agent_version, last_seen_at, gpu_temp_cap_c, max_concurrent_sessions, paused_at, paused_until, keep_free_gb, removed_at | A machine and its owner (ADR 0023); the name is the owner's | Removed hosts stay: the ledger points at them | Removed like "Remove", name cleared |
| `hosts.host_gpus` | host_id, **gpu_uuid**, model, vram_mb, driver_version, cuda_driver_version, compute_capability, first_seen_at, last_reported_at, gone_at | What the scheduler offers; the GPU's uuid is a hardware id | With the host | Deleted |
| `hosts.host_certificates` | serial, host_id, fingerprint_sha256, not_before, not_after, revoked_at, created_at | The agent's mutual-TLS certificates | With the host | Revoked; kept (no personal data) |
| `hosts.enrolment_codes` | host_id, code_hash, expires_at, created_at, used_at | An operator's enrolment code, hashed | With the host | Deleted |
| `hosts.price_listings` | host_id, workload_type, price_paise_per_min, created_at, updated_at | The owner's price | With the host | Kept (no personal data) |
| `hosts.status_changes` | id, host_id, from_status, to_status, **reason**, changed_by, created_at | A host's history: activated, suspended, removed | With the host | Kept; `changed_by` names an admin by id |
| `hosts.self_tests` | host_id, passed, distro_version, agent_version, checks, reported_at | The agent's last self-test | With the host | Deleted |
| `hosts.update_results` | host_id, version, outcome, detail, reported_at | How the last update went (ADR 0034) | With the host | Deleted |
| `hosts.host_links` | id, poll_token_hash, **machine_name**, os, agent_version, **gpus**, created_at, expires_at, approved_by, approved_at, host_id, denied_by, denied_at, redeemed_at, install_id | Linking a machine by approval (ADR 0032); the machine's name is Windows' computer name | `rule: host-links` | Deleted |
| `hosts.install_events` | id, install_id, step, outcome, reason, duration_ms, at, setup_version, windows_build, gpu_model, received_at, detail | How setup went (ADR 0033): nothing naming the person or machine, `detail` scrubbed by setup | `rule: install-events` | Those of their links' install ids are deleted |
| `hosts.idempotency_keys` | user_id, idempotency_key, request_hash, state, response_status, response_location, **response_body**, locked_at, created_at, expires_at | As identity's | `rule: idempotency-keys` | Deleted |

### agentgateway, scheduling

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `agentgateway.agent_commands` | id, host_id, session_id, type, **payload**, message_id, requires_ack, status, attempts, expires_at, created_at, sent_at, acked_at | Every message to an agent; a StartSession carries the renter's parameters and input links | `rule: agent-commands` | Their sessions' commands are deleted |
| `scheduling.offers` | id, session_id, host_id, attempt_number, status, decline_reason, expires_at, created_at, answered_at, session_attempt, **gpu_uuid** | Which host was offered a session | `rule: offers` | Kept (ids only, the GPU's uuid cleared) |

### sessions

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `sessions.sessions` | id, renter_id, host_id, type, state, attempt, template_id, image_digest, vram_needed_mb, max_price_paise_per_min, price_paise_per_min, max_spend_paise, max_runtime_seconds, lease_expires_at, idempotency_key, request_hash, state_changed_at, version, created_at, started_at, error_code, **params**, **presets** | A session: what ran, on which host, at what price; the parameters are the renter's | A money record: `legal.retention.money-records` | De-identified: params, presets, idempotency_key and request_hash cleared |
| `sessions.attempts` | session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base, resume_from_attempt, resume_from_seq, start_delivered_at, created_at, running_at, ended_at, price_paise_per_min, stop_requested_at, stop_reason, stop_deadline, grants_minted_at, refresh_queued_at, latest_log_seq | Each attempt's host and times, the basis of a charge | A money record | Kept (no personal data) |
| `sessions.session_events` | id, session_id, from_state, to_state, reason, at | The session's timeline | A money record | Kept |
| `sessions.lease_coverage` | id, session_id, attempt, covered_from, covered_until | The control plane's witness of each minute (ADR 0017) | A money record | Kept |
| `sessions.checkpoints` | id, session_id, attempt, object_key, step, created_at, completed_at | Where a checkpoint is | With its objects: `rule: checkpoints` | Deleted with the objects |
| `sessions.session_inputs` | session_id, **name**, upload_id, **url** | What a session took as input (ADR 0022) | With the session | Deleted |
| `sessions.uploads` | id, user_id, **name**, size_bytes, part_size_bytes, status, idempotency_key, request_hash, created_at, completed_at, expires_at, version, file_count, last_used_at | My files | `rule: uploads` (30 days after a session last used it; unfinished, a day) | Objects deleted, row deleted |
| `sessions.upload_files` | upload_id, **path**, stored_path, size_bytes, object_key, multipart_id, completed_at | Each file of an upload | With the upload | Deleted |

### metering, billing (money records)

Money records are kept for `legal.retention.money-records` (8 years, LEGAL(confirm)) and keyed by opaque ids. Nothing personal is in them beyond who (an id) and how much. The ledger is append-only by trigger; deleting money records after the period is a later decision (ADR 0037).

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `metering.metered_sessions` | session_id, renter_id, started_at, max_spend_paise, ended_at, closed_at, created_at, settlements, next_window_at, low_credit, stopped_for | Metering's view of a session | Money record | Kept |
| `metering.metered_attempts` | session_id, attempt, checked_through | | Money record | Kept |
| `metering.usage_minutes` | session_id, minute_index, minute_at, agent_reported, agent_attempt, gpu_seconds, avg_util_pct, lease_alive, lease_checked, lease_attempt, billed, waived, settlement_txn, refund_txn | Each minute's two witnesses (ADR 0017), partitioned by month | Money record | Kept |
| `metering.settlements` | txn_id, session_id, window_number, window_end, minutes, amount_paise, host_share_paise, commission_paise, settled_at | Each 15-minute settlement | Money record | Kept |
| `metering.settlement_parts` | txn_id, host_id, amount_paise, host_share_paise, minutes | | Money record | Kept |
| `metering.waivers` | session_id, attempt, host_id, checkpoint_at, waived_from, refund_txn, refunded_minutes, refunded_paise, host_refund_paise, platform_refund_paise, created_at | Lost-attempt waivers | Money record | Kept |
| `metering.review_minutes` | session_id, minute_index, minute_at, attempt, reason, created_at, resolution, **resolution_reason**, resolved_by, resolved_at, settlement_txn | Minutes one witness saw (ADR 0027) | Money record | Kept; `resolved_by` names an admin by id |
| `billing.ledger_entries` | id, txn_id, account, owner_id, amount_paise, direction, ref_type, ref_id, created_at | The double-entry ledger (ADR 0014), append-only | Money record | Kept: owner_id is the opaque id |
| `billing.wallets` | user_id, balance_paise, reserved_paise, version, updated_at | The ledger's cached balance | Money record | Kept, at zero after the leftover credit is forfeited |
| `billing.reservations` | id, user_id, session_id, amount_paise, status, created_at, released_at, committed_paise | Holds | Money record | Kept |
| `billing.reservation_changes` | id, reservation_id, session_id, reason, before_paise, after_paise, txn_id, created_at | Every hold change, append-only | Money record | Kept |
| `billing.credit_grants` | id, user_id, amount_paise, **reason**, granted_by, idempotency_key, txn_id, created_at | Free credit given (granting-credits.md); the reason is an admin's text | Money record | Kept; the reason was written by an admin and must not name the person |
| `billing.credit_grant_reversals` | id, grant_id, **reason**, reversed_by, txn_id, created_at | | Money record | Kept |
| `billing.payout_holds` | dispute_id, session_id, held_at, released_at | Earnings held while a report is open | Money record | Kept |
| `billing.reconciliation_runs` | id, trigger, finished_at, ledger_debits_paise, ledger_credits_paise, mismatches | The nightly wallet check | `rule: reconciliation-runs` | Kept |
| `billing.reconciliation_mismatches` | run_id, position, kind, user_id, wallet_paise, expected_paise | | With its run | Kept |

### trust, notifications

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `trust.ended_sessions` | session_id, renter_id, host_id, state, started_at, ended_at, recorded_at | Trust's own copy of what ratings and reports need (ADR 0028) | Money record | Kept |
| `trust.ratings` | session_id, renter_id, host_id, stars, **comment**, rated_at, changed_at | A renter's rating of a host | With the session | De-identified: the score stays for the host's average, the comment and the author (renter_id) go |
| `trust.host_ratings` | host_id, average_stars, count_at_least, refreshed_at | A host's average, worked out daily | With the host | Kept |
| `trust.disputes` | id, session_id, renter_id, reason, **details**, state, created_at, **resolution_reason**, resolved_by, resolved_at, refund_paise, commission_refund_paise, host_refund_paise, refund_txn | A renter's report about a session; a refund is a money record | Money record | De-identified: details and resolution reason cleared |
| `trust.idempotency_keys` | user_id, idempotency_key, request_hash, state, response_status, response_location, **response_body**, locked_at, created_at, expires_at | As identity's | `rule: idempotency-keys` | Deleted |
| `notifications.notifications` | id, user_id, kind, dedupe_key, **title**, **body**, link, created_at, in_app, read_at, email_wanted, email_claimed_at, email_sent_at | The in-app list and each email (ADR 0026) | `rule: notifications` | Deleted, after the last email is sent |
| `notifications.preferences` | user_id, kind, email, in_app, updated_at | What the person chose to be told | The account's life | Deleted |
| `notifications.email_suppressions` | **address_hash**, reason, event_id, created_at | Addresses that bounced or complained, as SHA-256 only | Until cleared by hand (email.md) | Deleted for the person's address |

### Shared

| Table | Columns (personal in bold) | Why | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `modulith.event_publication` | id, listener_id, event_type, **serialized_event**, publication_date, completion_date, status, completion_attempts, last_resubmission_date | Durable events between modules; an event carries ids and sometimes a name | Completed: `rule: event-publications` | Completed ones naming them deleted |
| `flyway.flyway_schema_history` | installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success | Flyway's own record | Always | Kept (no personal data) |

## Object storage

One bucket for sessions (`sessions.storage.bucket`), one for releases, one for backups. Keys are built only in `StorageLayout` (and `ExportLayout` for exports); every literal prefix in the control plane's source must appear here.

| Prefix | What | Personal | Kept | On erasure |
| --- | --- | --- | --- | --- |
| `uploads/` + `{id}/` | One upload | Below | `rule: uploads` | Below |
| `files/` (in an upload) + `{path}` | A renter's uploaded files, paths kept (ADR 0022) | The files and their names | `rule: uploads` | Deleted, then listed again to prove nothing is left |
| `sessions/` + `{id}/attempt-{n}/` | One attempt's objects | Below | Below | Below |
| `checkpoints/` (in an attempt) | The job's checkpoints | The renter's model state | `rule: checkpoints`, 7 days after the session ends | Deleted and verified |
| `outputs/` (in an attempt) | What the job gave back | The renter's results | `rule: outputs`, 30 days after the session ends | Deleted and verified |
| `logs/` (in an attempt) | The job's own output | Whatever the job printed | `rule: outputs`, with the outputs | Deleted and verified |
| `speedtest/` + `{host}/probe` | A host's upload speed test (no person) | No | Overwritten each test | Deleted with the host |
| `web/` | Not a storage prefix: source paths in the web app's source maps, read to name a browser error's line | No | Per release | Nothing |
| `basebackups_005/` (backups bucket) | WAL-G's full backups; `wal_005/` its WAL | Everything in the database | 30 days (`ops/backup/`), LEGAL(confirm) | Stays up to 30 days; a restore re-applies every erasure (`privacy.erased_users`) |
| `stable/` (releases bucket) | The signed release manifest and setup programs | No | Per release | Nothing |

## Valkey

Only state that may expire; never anything financial (ADR 0013).

| Key | What | Personal | Kept |
| --- | --- | --- | --- |
| `agentgateway:presence:{host}` | Which instance holds a host's stream | No | Expires with the presence lease |
| `agentgateway:telemetry:{host}` | 60 one-minute telemetry points | No | Expires; overwritten |
| `agentgateway:commands:{host}`, `sessions:changes` | Pub/sub hints, not stored | No | Not stored |
| `limits:requests:user:{id}:{window}`, `limits:requests:ip:{address}:{window}`, `limits:log-reads:…`, `limits:vitals:ip:{address}:…`, `limits:cli-logins:ip:{address}:…`, `limits:host-links:ip:{address}:…` | Request counters (ADR 0025) | An IP address | Expires after its window (a minute or an hour) |

## Logs, metrics and traces

| What | Personal | Kept |
| --- | --- | --- |
| The control plane's logs (structured JSON, to Grafana Cloud's Loki) | Ids only: `LogScan` fails the build when a test's logs hold an email, a token, a file path or name, or an IP | 14 days (monitoring.md) |
| Metrics (Prometheus) | Labels are codes only: no user, host, session or file in any label (`MetricLabelsTests`) | 14 days |
| Traces (Tempo) | Span attributes: the HTTP method, route template and status; the URL path, which holds ids; no query string, header, body or IP (`TraceAttributesTests`) | 14 days |
| Browser vitals and errors (`/v1/vitals`) | Route and release as codes; an error's message with anything shaped like an email, a token or a path removed | As logs and metrics |
| The agent's logs on a host | The agent's own lines, ids only | On the host, rotated |
| The job's logs on a host | Uploaded to `logs/` above; the scratch disk is deleted at session end | Session end |

## Outside our servers

| Where | What | Kept | On erasure |
| --- | --- | --- | --- |
| The sign-in provider (Firebase Authentication, Google Identity Platform) | Email, name, the provider account behind it, sign-in times and addresses | Google's terms | The identity is deleted through `SignInAccounts` (Admin API) |
| The email provider (Resend) | Each email: address, subject, body | 30 days of logs (email.md) | Ages out |
| The person's browser | Upload progress (IndexedDB: file names and parts' ETags), the sign-in SDK's own token store | Until they clear it | Theirs |
| The command line and SDK (`idleo`) | The key (keychain or a 0600 file), uploads' progress, pending bookings | Until `logout` | Theirs |
| A host's machine | While a session runs: the renter's inputs, scratch disk and container | Deleted at session end | Nothing left |

## Retention rules

Each rule is one scheduled job (`RetentionRule`), idempotent, run daily under an advisory lock, counted (`retention.deleted` by `rule`) and watched (`retention.last_success` by `rule`; the alert `RetentionJobStopped` fires when one hasn't succeeded for a day and a half). Periods live under `retention:` and `legal:` in application.yaml.

| Rule | Deletes | Period | Setting |
| --- | --- | --- | --- |
| (filled in as the jobs are built) | | | |
