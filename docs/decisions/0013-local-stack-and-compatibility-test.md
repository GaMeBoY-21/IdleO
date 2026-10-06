# 0013. The local stack and the compatibility test

- **Status:** Accepted
- **Date:** 2026-10-06

Sources:
- docs/plan/technical-design.md lines 96, 330, 719
- ADRs 0007, 0009, 0011 and 0012
- docs/prompts/local-stack.md

## Context

The control plane and the agent have each been proven only against test doubles. Before anything else is built on the stream, the real agent must run against the real control plane: on Nikki's Mac, where there is no NVIDIA GPU, and in CI on every change. That's the compatibility test the plan asks for (line 719).

It needs four things:
- the agent must be able to say it's a Mac;
- local runs need a sign-in without a real Google project;
- there must be an API that says whether a host is online;
- nothing that makes local runs possible may ever switch on in production.

## Decision

### Development hosts are a setting, refused in production

1. **The protocol gains `OPERATING_SYSTEM_MACOS = 3`,** commented "development only: a macOS machine has no NVIDIA GPU and is never scheduled". It's additive, and v1 is still open (ADR 0007); `buf breaking` passes. The agent reports it on macOS. V5 widens the `hosts.os` check to allow `macos`.
2. **One switch: `agentgateway.allow-development-hosts`.** It's false by default, and true in the `local` and `test` profiles. While it's false, `Hello` is refused with `INVALID_ARGUMENT` for:
   - `OPERATING_SYSTEM_MACOS`;
   - any GPU whose uuid starts with the agent's fake prefix, `GPU-00000000-fake`.
3. **`ProductionSettingsGuard` refuses to start the `prod` profile with a development setting.** It runs as a bean factory post-processor, before any other bean is created. A development setting is either:
   - `allow-development-hosts` set to true; or
   - a trusted identity issuer that isn't HTTPS, or is `localhost`, `127.0.0.1` or `::1`.

   Tests show both refusals, show that production settings start, and show that the `test` profile's own settings are exactly what the guard would refuse.
4. **The rule:** development-only behaviour is configuration, never a code path, and every new development setting goes into the guard (`CLAUDE.md`).

### Development sign-in: navikt/mock-oauth2-server

5. **[navikt/mock-oauth2-server](https://github.com/navikt/mock-oauth2-server) 6.0.4**, `ghcr.io/navikt/mock-oauth2-server:6.0.4@sha256:47374fe063995d9b6d39ebb6ea21eb5f765025bf0aa08b61b08b439eb91adaf0` (a multi-architecture index: amd64 and arm64).
   - **Why it:** it's a standard OIDC provider with discovery and a JWKS that issues signed RS256 tokens. Its claims come from a JSON file, it starts in seconds, and it's actively maintained: 6.0.4 shipped on 2026-09-29 and the repository was updated on 2026-10-05.
   - **Alternatives:** Keycloak would do, but it's heavyweight for a token mint. The Firebase Auth emulator issues unsigned tokens, which our decoder rightly refuses.
6. **Its tokens look like Identity Platform's.**
   - **How:** `control-plane/local-sign-in.json`, per the project's ["Token Customization via JSON_CONFIG"](https://github.com/navikt/mock-oauth2-server/blob/6.0.4/README.md#token-customization-via-json_config) section (`JSON_CONFIG_PATH`, `tokenCallbacks`, `requestMappings`, and `${clientId}` templating). A `client_credentials` request to issuer `idleo-local` returns a token with:
     - `aud` set to the fake project id `idleo-local`;
     - `sub` and `user_id` set to the client id;
     - `email` set to `<client id>@example.com`, with `email_verified: true`;
     - `name`, and a `firebase` claim.
   - **Issuer:** the server takes the issuer from the request URL, so it has a fixed port. `LOCAL_SIGN_IN_PORT` (default 8099) moves it, and compose and the `local` profile read the same variable.
7. **Compose and the tests run the same image and the same claim file.** `ComposeImageTests` keeps the images identical. The shared test context trusts the container as a third issuer, and `LocalSignInTests` signs in with one of its tokens.
8. **No development code in `identity`.** The `local` profile only trusts another issuer, and `IDENTITY_ISSUERS_0_*` still replaces it for testing against a real Google project.
   - **A bug the dev server found:** since Spring Security 7, `JwtClaimValidator` fails a missing claim before its predicate runs. That made our "optional" `auth_time` (ADR 0009) required. It's now a small validator that passes when the claim is absent, with tests for an absent and a future `auth_time`.

### Online in the host view, through an inverted port

9. **`GET /v1/hosts/{id}` and `GET /v1/hosts` now show:**
   - `online`;
   - `lastSeenAt` (written at most once a minute, line 96);
   - the reported GPUs: model, VRAM, and whether they're gone.
10. **`hosts` declares `HostPresence`** in its public API, and `agentgateway` implements it from the Valkey presence records, in one pipelined round trip per page.
    - **Why inverted:** `agentgateway` already calls `hosts`, so `hosts` calling `agentgateway` would be a module cycle, and Spring Modulith's verification fails on cycles. A port owned by `hosts` keeps every dependency pointing one way, and no module reads another's tables or keys.
11. **Valkey down:** every host shows offline, the response is still 200, and `agents.presence.unavailable` counts the miss. A host page never fails because presence is unavailable.

### The compatibility test

12. **`scripts/e2e/agent-online.sh`** starts the local stack, signs in, registers a host, enrols the real agent with a fake GPU, and watches `GET /v1/hosts/{id}` turn `online`, then not online after SIGTERM. It always tears everything down and checks that nothing is left running. It runs on the Mac and on Ubuntu (docs/runbooks/local-stack.md).
13. **`.github/workflows/e2e.yml` runs it on every change** to `control-plane/**`, `agent/**`, `proto/**` or the script. On failure, or when asked, it uploads `private/e2e/*.log`: logs only, never the agent's keys, config or tokens.
14. **What it proves today:**
    - The current agent works with the current control plane.
    - **The agent's HTTP/2 `:scheme` of `http` inside our TLS (ADR 0012) is accepted by grpc-java**, so the open question is closed.
    - **A clean agent shutdown removes presence at once.** The first runs measured offline within a fraction of a second of SIGTERM, not at the 15-second expiry.

## Alternatives considered

- **Writing our own token mint:** rejected. It would be a second, hand-made OIDC implementation to maintain, and the point is to exercise the standard path.
- **A development-only branch in `identity` (for example, skipping signature checks locally):** rejected. That's exactly the kind of code path that can leak into production.
- **Accepting `OPERATING_SYSTEM_UNSPECIFIED` from development builds:** rejected. Unspecified means a broken agent, and it should stay an error; a named value states the intent.
- **`hosts` reading Valkey itself, or presence copied into PostgreSQL:** rejected. The first breaks module ownership (ADR 0011); the second writes the money database on every heartbeat.
- **Valkey's key expiry as the only offline signal:** not needed. The test shows the clean close works, and expiry remains the fallback for crashes.

## Consequences

- **A Mac can be a development host** wherever the `local` profile runs, and nowhere else.
- **Every change to either side is checked against the other in CI.** Open question: the plan's full compatibility matrix (the last three agent releases against every server build, line 719) needs released agent builds. When releases exist, the e2e workflow gains a matrix over them.
- **The shared test context starts one more container,** the sign-in server, which adds a few seconds to the test run.
- **The fixed sign-in port means one local stack per machine** unless `LOCAL_SIGN_IN_PORT` moves it.
