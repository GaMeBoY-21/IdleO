#!/usr/bin/env bash
# The local cluster's Secrets (ADR 0045, 0046): SOPS files in deploy/clusters/local/secrets/idleo/, committed, encrypted
# for the local age key (private/k8s/age/local.agekey; scripts/deploy/age-key.sh local), the way staging's and
# production's are for theirs. Flux decrypts them in the cluster (up.sh --gitops); plain Helm (up.sh) has this apply
# them. What they hold is development's: the database's two users (CloudNativePG's basic-auth Secrets, ADR 0047) and the
# development MinIO's keys for its backups, the control plane's secrets from the development CA and keys
# (control-plane/scripts/dev-ca.sh), and the local CA the Gateway's certificate comes from.
#
#   scripts/k8s/secrets.sh            apply them and the development ConfigMaps to the cluster (plain Helm), and write
#                                     this machine's copies (below)
#   scripts/k8s/secrets.sh --files    only write this machine's copies (up.sh --gitops, smoke.sh)
#   scripts/k8s/secrets.sh --make     encrypt this machine's development secrets into the SOPS files: once, and again
#                                     only to change them (commit the result)
#
# This machine's copies, under the gitignored private/k8s/: the local CA (ca/ca.pem to trust, ca/ca.key for smoke.sh's
# name-constraint check), the database's passwords (database.env) and the agents' CA root (agent-ca.pem).
set -euo pipefail
log() { printf 'k8s: %s\n' "$*"; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
mode="${1:-apply}"
git check-ignore -q "$k8s_state/probe" || fail "refusing to write secrets: private/k8s/ is not gitignored"
mkdir -p "$k8s_state"
command -v sops >/dev/null || fail "sops isn't installed (macOS: brew install sops age)"
[[ -s "$SOPS_AGE_KEY_FILE" ]] ||
  fail "there's no local age key at private/k8s/age/local.agekey: it's the one the committed local Secrets are encrypted for (docs/runbooks/kubernetes.md)"
secrets_dir=deploy/clusters/local/secrets/idleo
secret_files="database-migration database-application backup-storage control-plane local-ca"

make_secrets() {
  local dev_ca="$k8s_root/private/dev-ca" database_env="$k8s_state/database.env" work
  control-plane/scripts/dev-ca.sh >/dev/null
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  # The database's passwords: kept if this machine has them.
  if [[ ! -s "$database_env" ]]; then
    (
      umask 077
      printf 'IDLEO_MIGRATION_PASSWORD=%s\n' "$(openssl rand -hex 24)"
      printf 'IDLEO_APPLICATION_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    ) >"$database_env"
  fi
  # The local CA for the Gateway's certificate: kept if this machine has one (a browser told to trust it keeps trusting
  # it). Its name constraints permit only localhost and names under it (*.idleo.localhost among them; every *.localhost
  # is this machine) and the loopback addresses, and its key usage only TLS servers, so trusting it can't make it vouch
  # for any other site: a browser refuses a certificate it signed for anything else (smoke.sh checks both). One made
  # before the constraints is made again. OpenSSL 3 (lib.sh): macOS's LibreSSL writes EC keys with explicit
  # parameters, which Kubernetes refuses.
  if [[ -s "$local_ca/ca.pem" ]] && ! openssl x509 -in "$local_ca/ca.pem" -noout -text 2>/dev/null | grep -q 'Name Constraints'; then
    log "making the local CA again, with name constraints (trust the new one, docs/runbooks/kubernetes.md)"
    rm -f "$local_ca/ca.pem" "$local_ca/ca.key"
  fi
  if [[ ! -s "$local_ca/ca.pem" ]]; then
    local openssl_bin
    openssl_bin="$(openssl3)"
    mkdir -p "$local_ca"
    (
      umask 077
      "$openssl_bin" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve \
        -out "$local_ca/ca.key"
    )
    "$openssl_bin" req -x509 -new -key "$local_ca/ca.key" -days 3650 -out "$local_ca/ca.pem" \
      -subj "/CN=IdleO local cluster CA" -addext "basicConstraints=critical,CA:true,pathlen:0" \
      -addext "keyUsage=critical,keyCertSign,cRLSign" -addext "extendedKeyUsage=serverAuth" \
      -addext "nameConstraints=critical,permitted;DNS:localhost,permitted;IP:127.0.0.0/255.0.0.0,permitted;IP:0:0:0:0:0:0:0:1/FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF"
  fi

  manifest() { kubectl create --namespace "$namespace" "$@" --dry-run=client -o yaml; }
  password() { sed -n "s/^$1=//p" "$database_env"; }
  manifest secret generic idleo-database-migration --type=kubernetes.io/basic-auth \
    --from-literal=username=idleo_migration --from-literal=password="$(password IDLEO_MIGRATION_PASSWORD)" \
    >"$work/database-migration.yaml"
  manifest secret generic idleo-database-application --type=kubernetes.io/basic-auth \
    --from-literal=username=idleo_app --from-literal=password="$(password IDLEO_APPLICATION_PASSWORD)" \
    >"$work/database-application.yaml"
  # The development MinIO's own keys (templates/development.yaml), not a secret, but a Secret as staging's R2 keys are.
  manifest secret generic idleo-backup-storage --from-literal=ACCESS_KEY_ID=local-storage-root \
    --from-literal=ACCESS_SECRET_KEY=local-only-not-a-secret >"$work/backup-storage.yaml"
  # The control plane's: PEM and base64 content, each under the name of the setting it fills (application-cluster.yaml
  # names the TLS bundle's and the CA's; the keys go by their properties' own names).
  key() { tr -d '[:space:]' <"$dev_ca/$1"; }
  manifest secret generic idleo-control-plane \
    --from-file=AGENT_GRPC_TLS_CERTIFICATE="$dev_ca/server-chain.pem" \
    --from-file=AGENT_GRPC_TLS_PRIVATE_KEY="$dev_ca/server.key" \
    --from-file=HOSTS_CA_ROOT_CERTIFICATE="$dev_ca/root.pem" \
    --from-file=HOSTS_CA_INTERMEDIATE_CERTIFICATE="$dev_ca/intermediate.pem" \
    --from-file=HOSTS_CA_INTERMEDIATE_PRIVATE_KEY="$dev_ca/intermediate.key" \
    --from-literal=IDENTITY_INVITECODEKEY="$(key invite-code.key)" \
    --from-literal=IDENTITY_ADMIN_TOTPKEY="$(key admin-totp.key)" \
    --from-literal=PAYOUTS_PAN_KEY="$(key pan.key)" \
    --from-literal=IMAGES_REGISTRYTOKEN_KEY="$(key registry-token.key)" \
    --from-literal=IMAGES_SCANNER_TOKEN="$(key scanner.token)" >"$work/control-plane.yaml"
  manifest secret tls idleo-local-ca --cert="$local_ca/ca.pem" --key="$local_ca/ca.key" >"$work/local-ca.yaml"

  local name
  for name in $secret_files; do
    # kubectl's empty creationTimestamp would make every run a change.
    sed -i.bak '/creationTimestamp: null/d' "$work/$name.yaml"
    sops encrypt --filename-override "$secrets_dir/$name.yaml" "$work/$name.yaml" >"$secrets_dir/$name.yaml"
  done
  log "the local Secrets are encrypted in $secrets_dir/ for the local age key; commit them"
}

# A key of one of the SOPS files, decrypted and decoded.
secret_value() {
  sops decrypt "$secrets_dir/$1.yaml" | python3 -c '
import base64, sys, yaml
print(base64.b64decode(yaml.safe_load(sys.stdin)["data"][sys.argv[1]]).decode(), end="")' "$2"
}

write_files() {
  mkdir -p "$local_ca"
  (
    umask 077
    secret_value local-ca tls.key >"$local_ca/ca.key"
    {
      printf 'IDLEO_MIGRATION_PASSWORD=%s\n' "$(secret_value database-migration password)"
      printf 'IDLEO_APPLICATION_PASSWORD=%s\n' "$(secret_value database-application password)"
    } >"$k8s_state/database.env"
  )
  secret_value local-ca tls.crt >"$local_ca/ca.pem"
  secret_value control-plane HOSTS_CA_ROOT_CERTIFICATE >"$k8s_state/agent-ca.pem"
}

case "$mode" in
  --make)
    make_secrets
    ;;
  --files)
    write_files
    ;;
  apply)
    write_files
    kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace" >/dev/null
    for name in $secret_files; do
      sops decrypt "$secrets_dir/$name.yaml" | kubectl apply -f - >/dev/null
    done
    kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/clusters/local/development | kubectl apply -f - >/dev/null
    log "secrets and development settings are in namespace $namespace"
    ;;
  *) fail "usage: $0 [--files | --make]" ;;
esac
