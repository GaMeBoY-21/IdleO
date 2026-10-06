# Activating and suspending hosts (by Nikki)

A host only gets work once it's **active** (ADR 0015). Onboarding's benchmark and canary job (technical design line 635) don't exist yet, so for now an operator activates hosts by command, the same way free credit is granted (docs/runbooks/granting-credits.md):
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
