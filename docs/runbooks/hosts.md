# Activating and suspending hosts (by Nikki)

A host only gets work once it's **active** (ADR 0015). Onboarding's benchmark and canary job (technical design line 667) don't exist yet, so for now an operator activates hosts by command, the same way free credit is granted (docs/runbooks/granting-credits.md):
- every change is an append-only row in `hosts.status_changes`, saying who, why, from and to, and when;
- asking for a status the host already has changes nothing.

## Before activating

The command refuses a host unless all three of these hold:

1. **Its agent enrolled,** and the host has a valid certificate that isn't revoked.
2. **Its owner published a listing** (`PUT /v1/hosts/{id}/listing`):
   - the batch price per minute, at least 1 paisa;
   - a GPU temperature cap between 60 and 95 °C;
   - `maxConcurrentSessions`, which is 1 for now.
3. **Its agent connected at least once,** so the control plane knows its GPUs, and at least one of them isn't gone.

In Phase 1 an active host is available whenever it's online and idle. There are no availability windows yet.

## The commands

```
OPERATOR=<you> <control plane> --spring.profiles.active=<prod or local>,ops \
  activate-host --host <host id> --reason "<why>"

OPERATOR=<you> <control plane> --spring.profiles.active=<prod or local>,ops \
  suspend-host --host <host id> --reason "<why>"
```

- **Locally**, from `control-plane/`, against the `scripts/e2e/agent-online.sh --keep-running` stack:
  ```
  COMPOSE_PROJECT_NAME=idleo-e2e OPERATOR=nikki ./mvnw -q spring-boot:run \
    -Dspring-boot.run.profiles=local,ops \
    -Dspring-boot.run.arguments="activate-host --host <host id> --reason 'Pilot host'"
  ```
- **In production:** `OPERATOR=nikki java -jar control-plane.jar --spring.profiles.active=prod,ops activate-host …`, on a control-plane machine with the deployment's environment.

**Output and exit codes:**
- It prints `host <id>: pending -> active, by nikki`, or `host <id> is already active; nothing changed`.
- Exit codes are 0 done, 2 a bad command line, and 3 refused (with what's missing).

## Suspending

A suspended host gets no new work, and its agent is refused (`PERMISSION_DENIED`) at its next connection, after which it retries every 10 minutes (ADR 0011, ADR 0012). To bring it back, activate it again: the same checks apply.

## Checking

Read-only:

```sql
select host_id, from_status, to_status, reason, changed_by, created_at
from hosts.status_changes order by created_at desc limit 10;
```

The owner sees the status, the listing, `online` and the GPUs in `GET /v1/hosts/{id}`.

## What an owner can do for themselves (ADR 0023)

Pausing, renaming, removing and the disk kept free are the owner's, from the web app's Hosting tab or the API; an operator doesn't need to do them.

**Pause** (`POST /v1/hosts/{id}/pause`, `.../resume`):
- A paused host gets no new offers, and a session it's running finishes. It's still online and still counts as active.
- `until` is optional and must be within 7 days; a pause with an end ends by itself, with nothing to run. One with no end lasts until the owner resumes.
- If a host isn't being offered work and its owner doesn't know why, check `paused` and `pausedUntil` in `GET /v1/hosts/{id}`, then its free disk (below).

**Keep free on my disk** (`keepFreeGb` in the listing, 10 to 500, default 20): a host is offered a session only when its free disk less this covers the session's files and scratch space. A host that reports 25 GB free with the default is offered sessions needing up to 5 GB. A host that never reports its free disk gets none.

**Remove** (`DELETE /v1/hosts/{id}`):
- Refused with 409 `host-busy` while the host has a session that hasn't ended: pause it and try again after the session.
- Otherwise it revokes the host's certificates and enrolment code, closes its agent's stream, and drops it from its owner's lists. The agent can't come back with its old certificate; the owner adds the machine again and enrols with a new code.
- The row stays, because the ledger points at it. `hosts.status_changes` has a row for it, "removed by its owner". The operator command `activate-host` refuses a removed host.
- Its earnings stay in its owner's total.

**The suggested price** (`GET /v1/hosts/{id}/price-band`): the range among other active hosts with the same GPU model, leaving out the top and bottom 10%. With fewer than 3 such hosts it falls back to `scheduling.price-band-defaults` in `application.yaml`, by the GPU's memory class, in paise a minute (1 paisa a minute is ₹0.60 an hour):

| GPU memory class | Low | High | An hour |
| --- | --- | --- | --- |
| 8 GB | 8 | 14 | ₹4.80 to ₹8.40 |
| 12 GB | 12 | 20 | ₹7.20 to ₹12.00 |
| 16 GB | 16 | 28 | ₹9.60 to ₹16.80 |
| 24 GB | 24 | 42 | ₹14.40 to ₹25.20 |

These are starting guesses. Change them in `application.yaml` (no code change) once real listings show what hosts charge; a change needs a deploy.

**Telemetry** (`GET /v1/hosts/{id}/telemetry`): the latest GPU readings and the last hour, a point a minute, kept in Valkey (`agentgateway:telemetry:{host id}`, a list of 60, gone an hour after the last heartbeat). It feeds the owner's screen only. If Valkey is down the screen shows no history; nothing else is affected.

**Earnings** (`GET /v1/earnings`, `GET /v1/hosts/{id}/earnings`): read from the ledger's `host_payable` entries. To check a figure, sum them:

```sql
select owner_id as host_id,
       sum(case direction when 'credit' then amount_paise else -amount_paise end) as earned_paise
from billing.ledger_entries
where account = 'host_payable'
group by owner_id;
```

The owner-wide total is the sum of this over the hosts the person owns, removed ones too. Nothing is paid out yet: payouts start with the paid pilot.
