# The local stack: the real agent online against the real control plane

`scripts/e2e/agent-online.sh` runs the whole platform on one machine and checks the agent coming online end to end. It's also CI's compatibility test (`.github/workflows/e2e.yml`, ADR 0013): every change to the control plane, the agent or the protocol is proven against the other side.

## What it does

From the repository root, it:

1. Creates the development CA under `private/dev-ca/` if it doesn't exist yet (`control-plane/scripts/dev-ca.sh`), including the gRPC port's certificate for `localhost`.
2. Starts the control plane on the `local` profile (`./mvnw spring-boot:run`).
   - Spring Boot starts `control-plane/compose.yaml`: PostgreSQL, Valkey, MinIO (object storage) and the development sign-in server, under the compose project name `idleo-e2e`.
   - It uses the development template catalog (`templates/development.yaml`: the smoke test runs 20 s, `charged-run` 150 s and `long-run` 10 minutes, each checkpointing every 5 s) and a 10-second lease renewed every 3 s (`E2E_LEASE_TTL`; production uses 2 minutes and 30 s).
   - Storage grants last 60 s, so every run outlives its first and the agents swap in fresh ones. Metering ticks every second and closes a session 20 s after it ends rather than 5 minutes.
   - The script waits for readiness to be UP.
3. Gets an ID token for an invented user (`e2e-…@example.com`) from the development sign-in server.
4. Registers four hosts (`POST /v1/hosts` with an `Idempotency-Key`) and gets their enrolment codes (`POST /v1/hosts/{id}/enrolment-code`). Codes are never printed or logged.
5. Builds the agent (`cargo build --release`), writes `private/e2e/agent-1.toml` to `agent-4.toml`, and enrols each into its own `private/e2e/agent-N/`.
6. Runs four `idleo-agent run --fake-gpu --dev-runner`, and polls `GET /v1/hosts/{id}` every 500 ms until every host is `online` (30 s at most). It also checks that the fake GPU is listed.
7. **Sets up money and hosts:**
   - two renters get ₹500 each with the `grant-credits` operator command: A for the normal run alone, so its wallet shows exactly that charge, and B for the other three;
   - every host lists at 2 paise a minute, and is activated with `activate-host`.
8. **Runs four charged sessions at once, one per host** (ADRs 0015, 0016, 0017), each in its own subshell:
   - **Normal run** (`charged-run`, renter A): runs 150 s and completes. The output (`private/e2e/output-normal.json`) is attempt 1 with no resume. It's charged exactly 3 minutes, 6 paise, in one balanced ledger transaction (renter −6, host +4, platform +2), which the summary prints. The wallet goes from ₹500.00 to ₹499.94 with nothing held, and `GET /v1/hosts/{id}/earnings` shows +4 paise for the run.
   - **Stop mid-run** (`long-run`, renter B): about 70 s after RUNNING, `POST /v1/sessions/{id}/stop`. The agent writes a last checkpoint and stops, and the session ends `COMPLETED` (`renter_stopped`, then `stopped`), charged exactly the minutes it ran.
   - **Host loss** (`charged-run`, renter B): once minute 0 is reported and a checkpoint is past it, `kill -9` the agent. The lease lapses, the session goes `HOST_LOST` and back to `REQUESTED` as attempt 2, and resumes on a free host from the newest complete checkpoint (`private/e2e/output-resumed.json` names it); the killed agent is restarted. Attempt 2 only ran after attempt 1 was declared lost. Attempt 1's minutes after its checkpoint aren't charged and attempt 2's are; the summary prints the waiver and the ledger.
   - **Spending cap** (`long-run` with `maxSpendPaise` 6, renter B): stopped with `max_spend_reached` after a last checkpoint, `COMPLETED`, charged no more than 6 paise and exactly the minutes it ran.

   Then it checks renter B's wallet is not negative and holds nothing, that an agent resumed from a checkpoint and swapped in fresh credentials, and that no storage credential appears in any log.
9. **Runs the wallet check** (`reconcile-wallets`) and requires 0 mismatches.
10. Stops the agents with SIGTERM, and polls until every host is offline (30 s at most). Under 5 seconds means the clean close removed presence; anything longer would mean only the 15-second presence expiry did, and the script says which.
11. Prints a summary, with each timing and PASS or FAIL, and exits non-zero on any failure.
12. On exit, even after a failure or Ctrl-C, it stops the agents and the control plane and removes the compose services. It then checks that no container, control plane or agent is still running.

Logs go to `private/e2e/`: `control-plane.log`, `agent-1.log` to `agent-4.log`, `agent-build.log`, `ops.log` (the operator commands) and `compose.log`, with the two downloaded outputs beside them. Like everything else under `private/`, they're gitignored. The script never reads `control-plane/.env`, so a local override can't change the result.

## What you need

- **Docker**, running.
- **Java 25.** On a Mac: `brew install openjdk@25`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
- **Rust**, through rustup. It installs the toolchain pinned in `agent/rust-toolchain.toml` on first use. Homebrew's rustup (`/opt/homebrew/opt/rustup/bin`) is found even when it isn't on `PATH`.
- **OpenSSL 3**, for the development CA. macOS ships LibreSSL: `brew install openssl@3`.
- `curl`, `perl` and `python3`, which macOS and Ubuntu both have.

## Run the check

```
scripts/e2e/agent-online.sh
```

The first run downloads the images, the Maven dependencies and the crates, and builds both sides. After that, a run takes about a minute and a half on a Mac: starting the control plane, two operator commands, two 20-second jobs, and the lost host's 10-second lease.

## Keep the stack up and try things by hand

```
scripts/e2e/agent-online.sh --keep-running
```

This starts only the stack (steps 1 to 3) and prints a ready `curl` with a fresh token, for example:

```
curl -s -H 'Authorization: Bearer eyJ…' http://localhost:8080/v1/me
```

It also prints the command for a new token; tokens last an hour. Any `client_id` signs in as a different invented user, `<client_id>@example.com`. To run an agent against the stack by hand, follow `agent/README.md`, with `ca_root` set to `private/dev-ca/root.pem` and the port set to 9443.

**Ctrl-C stops everything.** If the terminal was closed instead, run `docker compose -p idleo-e2e -f control-plane/compose.yaml down` and stop any leftover `spring-boot:run` process.

## Ports

| Port | What | Moved with |
| --- | --- | --- |
| 8080 | The control plane's HTTP API | `E2E_HTTP_PORT` |
| 9443 | The agents' gRPC port (TLS) | `E2E_GRPC_PORT` |
| 8099 | The development sign-in server. The issuer URL in every token includes it, so it's fixed rather than random | `LOCAL_SIGN_IN_PORT` (compose and the `local` profile read the same variable) |
| 9100 | MinIO's S3 API. Agents' grants and renters' download links carry this endpoint, so it's fixed | `LOCAL_STORAGE_PORT` (compose and the `local` profile read the same variable) |

PostgreSQL and Valkey get random ports. If a port is busy, the script stops before starting anything and names the port. A stack already started by `control-plane/scripts/run-local.sh` uses the same fixed ports, so stop it first.

## When it fails

- **"the control plane exited during startup":** read `private/e2e/control-plane.log`. Docker not running, or a port taken inside Docker, are the usual causes.
- **"the host didn't come online":** read `private/e2e/agent.log`. A TLS error there usually means a stale development CA: run `control-plane/scripts/dev-ca.sh --force`, then run the script again.
- **A session doesn't reach `RUNNING` or `COMPLETED`:** read `private/e2e/agent.log` for the agent's view (a refused StartSession, a storage failure) and `control-plane.log` for "Couldn't mint storage credentials". MinIO is reached at `http://localhost:9100`. Object storage is described in docs/runbooks/object-storage.md.
- **"the host didn't go offline within 30 s":** the agent didn't close its stream, and presence also didn't expire. Compare the agent's and the control plane's logs around the SIGTERM.

## Nothing here can reach production

The development sign-in server is a local issuer, and only the `local` and `test` profiles trust it. Fake GPUs and Macs are accepted only with `agentgateway.allow-development-hosts`. The `prod` profile refuses to start with either (ADR 0013).
