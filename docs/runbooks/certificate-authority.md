# Certificate authority: the root ceremony and yearly rotation (by the product owner, before production)

**Nothing from this runbook is ever committed.** Keys, passwords and certificates stay on your machine, on your offline backups, and in the production secret store. Claude Code never handles them.

Our own CA issues every agent's client certificate and the gRPC server's certificate (ADR 0010).
- **The root** is offline, kept encrypted on your machine and backups. It signs only the intermediate.
- **The intermediate** is online, in the secret store. It signs everything else.

Agents trust only our root.

All commands use OpenSSL 3. macOS ships LibreSSL, so use Homebrew's:
```
brew install openssl@3
export OSSL=/opt/homebrew/opt/openssl@3/bin/openssl
```
Every key is ECDSA P-256.

## 1. Create the root (once, on your own machine)

Do this in a new folder outside the repo, ideally with the network off.

1. **Create the root key**, encrypted with a long passphrase. Type it yourself, and store it in your password manager.
   ```
   $OSSL genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out root.key
   ```
2. **Self-sign the root certificate for 10 years.** It may sign one level of CA below it.
   ```
   printf 'basicConstraints=critical,CA:true,pathlen:1\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n' > root.ext
   $OSSL req -new -key root.key -subj "/CN=IdleO Root CA" -out root.csr
   $OSSL x509 -req -in root.csr -key root.key -days 3650 -sha256 \
     -set_serial 0x$($OSSL rand -hex 16) -extfile root.ext -out root.pem
   ```
3. **Back it up.** Copy `root.key` (encrypted) and `root.pem` to two encrypted USB drives, kept in two different places. Then delete `root.key` from the laptop, or keep it only inside an encrypted disk image that is unmounted when not in use.

`root.pem` is public: it goes to the control plane and into every agent.

## 2. Create and sign the intermediate (now, and once a year)

1. **Create the intermediate key**, encrypted with its own passphrase. This passphrase goes into the secret store.
   ```
   $OSSL genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out intermediate.key
   ```
2. **Sign it with the root for 13 months.** The extra month gives the yearly rotation time to overlap.
   ```
   printf 'basicConstraints=critical,CA:true,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n' > intermediate.ext
   $OSSL req -new -key intermediate.key -subj "/CN=IdleO Intermediate CA $(date +%Y)" -out intermediate.csr
   $OSSL x509 -req -in intermediate.csr -CA root.pem -CAkey root.key -days 395 -sha256 \
     -set_serial 0x$($OSSL rand -hex 16) -extfile intermediate.ext -out intermediate.pem
   ```
3. **Check it:**
   ```
   $OSSL verify -CAfile root.pem intermediate.pem
   ```

## 3. Issue the gRPC server certificate (with the intermediate)

Replace `agents.example.invalid` with the real hostname the agents dial.

```
$OSSL genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out server.key
printf 'basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:agents.example.invalid\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n' > server.ext
$OSSL req -new -key server.key -subj "/CN=agents.example.invalid" -out server.csr
$OSSL x509 -req -in server.csr -CA intermediate.pem -CAkey intermediate.key -days 90 -sha256 \
  -set_serial 0x$($OSSL rand -hex 16) -extfile server.ext -out server.pem
cat server.pem intermediate.pem > server-chain.pem
```

The certificate has `serverAuth` only, and it's renewed every 90 days the same way.

## 4. Put it in the secret store

Set these in the production deployment's secret store, never in a file in the repo. Each value is the file's PEM content, or a `file:` path to where the platform mounts the secret.

| Variable | Value |
| --- | --- |
| `HOSTS_CA_ROOT_CERTIFICATE` | `root.pem` |
| `HOSTS_CA_INTERMEDIATE_CERTIFICATE` | `intermediate.pem` |
| `HOSTS_CA_INTERMEDIATE_PRIVATE_KEY` | `intermediate.key` (encrypted) |
| `HOSTS_CA_INTERMEDIATE_PRIVATE_KEY_PASSWORD` | the intermediate's passphrase |
| `AGENT_GRPC_TLS_CERTIFICATE` | `server-chain.pem` |
| `AGENT_GRPC_TLS_PRIVATE_KEY` | `server.key` |

The control plane refuses to start if the intermediate isn't a CA, doesn't match its key, or wasn't signed by the root.

Then delete the working copies of `intermediate.key` and `server.key` from your laptop. The root key goes back offline.

## 5. Rotate the intermediate yearly

1. A month before the intermediate expires, repeat step 2 with a new key, then step 3 with the new intermediate.
2. Update the secret store and redeploy. New agent certificates come from the new intermediate. Existing ones stay valid until their 30 days run out, because they chain to the same root.
3. On suspicion of compromise, rotate at once, without waiting for the year to end.

The root itself is replaced only before its 10 years run out, or if it's compromised. Either means shipping the new root to every agent, so plan that as its own piece of work.

## Local development

You don't need any of this locally. `control-plane/scripts/dev-ca.sh` creates a throwaway root, intermediate and `localhost` certificate under the gitignored `private/dev-ca/`, and `scripts/run-local.sh` uses it.
