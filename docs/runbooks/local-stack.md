# The local stack: the real agent online against the real control plane

`scripts/e2e/agent-online.sh` runs the whole platform on one machine and checks the agent coming online end to end. It's also CI's compatibility test (`.github/workflows/e2e.yml`, ADR 0013): every change to the control plane, the agent or the protocol is proven against the other side.

## What it does

From the repository root, it:

1. Creates the development CA under `private/dev-ca/` if it doesn't exist yet (`control-plane/scripts/dev-ca.sh`), including the gRPC port's certificate for `localhost`.
2. Starts the control plane on the `local` profile (`./mvnw spring-boot:run`).
   - Spring Boot starts `control-plane/compose.yaml`: PostgreSQL, Valkey, and the development sign-in server, under the compose project name `idleo-e2e`.
   - The script waits for readiness to be UP.
3. Gets an ID token for an invented user (`e2e-…@example.com`) from the development sign-in server.
4. Registers a host (`POST /v1/hosts` with an `Idempotency-Key`) and gets its enrolment code (`POST /v1/hosts/{id}/enrolment-code`). The code is never printed or logged.
5. Builds the agent (`cargo build --release`), writes `private/e2e/agent.toml`, and enrols it into `private/e2e/agent/`.
6. Runs `idleo-agent run --fake-gpu`, and polls `GET /v1/hosts/{id}` every 500 ms until `online` is true (30 s at most). It also checks that the fake GPU is listed.
7. **Books a session** (ADR 0015):
   - gives the user ₹500 with the `grant-credits` operator command;
   - sets the host's listing to 2 paise a minute, and activates it with `activate-host`;
   - books the smoke template (`POST /v1/sessions`).

   The real agent accepts the offer, so the session reaches `PROVISIONING`, and the wallet shows 60 paise held (30 minutes at 2 paise). The script records the time from the POST to `MATCHED` and to `PROVISIONING`, from the session's own events.
8. **Waits for the provisioning timeout** (`E2E_PROVISIONING_TIMEOUT`, 10 s here, 3 minutes in production). Nothing starts sessions yet, so the session fails with `start_timeout`, and the wallet is back to ₹500 exactly. Its `session_events` are printed in the summary.
9. **Books again and cancels while `PROVISIONING`:** the state is `CANCELLED`, and the hold is released.
10. Stops the agent with SIGTERM, and polls until `online` is false (30 s at most). Under 5 seconds means the clean close removed presence; anything longer would mean only the 15-second presence expiry did, and the script says which.
11. Prints a summary, with each timing and PASS or FAIL, and exits non-zero on any failure.
12. On exit, even after a failure or Ctrl-C, it stops the agent and the control plane and removes the compose services. It then checks that no container, control plane or agent is still running.

Logs go to `private/e2e/`: `control-plane.log`, `agent.log`, `agent-build.log`, `ops.log` (the operator commands) and `compose.log`. Like everything else under `private/`, they're gitignored. The script never reads `control-plane/.env`, so a local override can't change the result.

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

The first run downloads the images, the Maven dependencies and the crates, and builds both sides. After that, a run takes about 35 seconds on a Mac: starting the control plane, two operator commands, and the 10-second provisioning timeout.

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

PostgreSQL and Valkey get random ports. If a port is busy, the script stops before starting anything and names the port. A stack already started by `control-plane/scripts/run-local.sh` uses the same fixed ports, so stop it first.

## When it fails

- **"the control plane exited during startup":** read `private/e2e/control-plane.log`. Docker not running, or a port taken inside Docker, are the usual causes.
- **"the host didn't come online":** read `private/e2e/agent.log`. A TLS error there usually means a stale development CA: run `control-plane/scripts/dev-ca.sh --force`, then run the script again.
- **"the host didn't go offline within 30 s":** the agent didn't close its stream, and presence also didn't expire. Compare the agent's and the control plane's logs around the SIGTERM.

## Nothing here can reach production

The development sign-in server is a local issuer, and only the `local` and `test` profiles trust it. Fake GPUs and Macs are accepted only with `agentgateway.allow-development-hosts`. The `prod` profile refuses to start with either (ADR 0013).
