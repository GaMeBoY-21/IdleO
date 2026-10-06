# Object storage for sessions (by Nikki)

Jobs read their input and write checkpoints and outputs in S3-compatible object storage (ADR 0016):
- **Cloudflare R2** in production;
- **MinIO**, from `control-plane/compose.yaml`, locally and in CI.

Agents never get a long-lived key. For each delivery of a StartSession, the control plane mints two short-lived grants limited to the attempt's folders, and storage enforces the limit. Renters download outputs through pre-signed links valid for 15 minutes.

**Nothing is created yet.** This page is what to set up before the first production deployment. Never put a key or token in git, and never send one in chat: they go only into the deployment's secret store.

## The layout

One bucket per environment, laid out exactly as `proto/README.md` "Storage layout":

```
sessions/{session_id}/input/                                  the job's input; agents read it only
sessions/{session_id}/attempt-{n}/checkpoints/{seq:08}/...    a checkpoint, _COMPLETE written last
sessions/{session_id}/attempt-{n}/outputs/...                 the outputs, _COMPLETE written last
```

## Setting up R2 (once per environment)

1. **A bucket.** In the Cloudflare dashboard, go to R2, then Create bucket. Name it per environment, for example `idleo-prod-sessions`, with automatic location (or Asia-Pacific). Leave public access off.
2. **The parent access key.** Under R2, then Manage R2 API tokens, create an **Account API token** with permission *Object Read & Write*, scoped to that bucket only. Cloudflare shows an *Access Key ID* and a *Secret Access Key* once. Put both straight into the secret store.
   - The control plane uses this key itself, to check completion markers, list outputs and sign download links.
   - It's also the parent that every temporary agent credential derives from. Temporary credentials can never exceed their parent, which is why it covers this one bucket.
3. **An API token to mint temporary credentials.** Under My Profile (or the account), then API Tokens, create a token with **Account → Workers R2 Storage → Edit** for this account. It calls `POST /accounts/{account_id}/r2/temp-access-credentials`: https://developers.cloudflare.com/api/resources/r2/subresources/temporary_credentials/methods/create/. Put it in the secret store.
4. **The account id** is on the R2 overview page. The S3 endpoint is `https://<account id>.r2.cloudflarestorage.com`.

## Environment variables (prod profile)

| Variable | Value |
| --- | --- |
| `SESSIONS_STORAGE_ENDPOINT` | `https://<account id>.r2.cloudflarestorage.com` |
| `SESSIONS_STORAGE_BUCKET` | The bucket's name |
| `SESSIONS_STORAGE_ACCESS_KEY_ID` | The parent key's Access Key ID (step 2) |
| `SESSIONS_STORAGE_SECRET_ACCESS_KEY` | The parent key's Secret Access Key (step 2), a secret |
| `SESSIONS_STORAGE_R2_ACCOUNT_ID` | The Cloudflare account id (step 4) |
| `SESSIONS_STORAGE_R2_API_TOKEN` | The API token from step 3, a secret |

The prod profile always uses `r2` and refuses to start with `minio` (`ProductionSettingsGuard`).

Grants last 1 hour (`sessions.storage.grant-ttl`), and download links 15 minutes (`sessions.storage.download-link-ttl`).

## Checking it works

After deploying to staging, book the smoke test with a test host.
- The control plane's log shows the session `running`.
- An error minting credentials is logged as "Couldn't mint storage credentials", with Cloudflare's error code and never a secret. The StartSession stays queued and is retried, and the provisioning timeout fails the session after 3 minutes.

Common causes of a minting error:
- the API token lacks R2 edit permission;
- the parent key isn't scoped to the bucket;
- the account id is wrong.

## Rotating

1. Create a new parent key or API token.
2. Update the secret store.
3. Redeploy.
4. Delete the old one.

Grants already issued keep working until they expire, at most an hour. Running jobs then lose storage access, and `RefreshCredentials` isn't built yet (ADR 0016), so rotate when no long job is running.

## Locally

`compose.yaml` runs MinIO on `127.0.0.1:9100` (`LOCAL_STORAGE_PORT` moves it), with the throwaway root user `local-storage-root`. The control plane creates the `idleo-local` bucket at startup and mints grants with MinIO's STS. The MinIO console isn't exposed. To look inside, use any S3 client against `http://localhost:9100` with the root user from `compose.yaml`.
