#!/usr/bin/env bash
# The local cluster's Secrets and development ConfigMaps (ADR 0045), made from this machine's development keys, never
# committed: the database's three passwords (made once, kept in private/k8s/database.env, since the database's volume
# keeps them), the control plane's secrets from the development CA and keys (control-plane/scripts/dev-ca.sh), the
# local CA the Gateway's certificate comes from, and the development catalog and sign-in server's settings. Run by
# up.sh; safe to run again. Staging and production get theirs from SOPS (S2).
set -euo pipefail
log() { printf 'k8s: %s\n' "$*"; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
dev_ca="$k8s_root/private/dev-ca"
git check-ignore -q "$k8s_state/probe" || fail "refusing to write secrets: private/k8s/ is not gitignored"
mkdir -p "$k8s_state"

control-plane/scripts/dev-ca.sh >/dev/null

# A Secret or ConfigMap applied whole, so running this again updates it.
apply() { kubectl -n "$namespace" create "$@" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; }

database_env="$k8s_state/database.env"
if [[ ! -s "$database_env" ]]; then
  umask 077
  {
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'IDLEO_MIGRATION_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'IDLEO_APPLICATION_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  } >"$database_env"
fi
kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace" >/dev/null
apply secret generic idleo-database --from-env-file="$database_env"

# The control plane's: PEM and base64 content, each under the name of the setting it fills (application-cluster.yaml
# names the TLS bundle's and the CA's; the keys go by their properties' own names).
key() { tr -d '[:space:]' <"$dev_ca/$1"; }
apply secret generic idleo-control-plane \
  --from-file=AGENT_GRPC_TLS_CERTIFICATE="$dev_ca/server-chain.pem" \
  --from-file=AGENT_GRPC_TLS_PRIVATE_KEY="$dev_ca/server.key" \
  --from-file=HOSTS_CA_ROOT_CERTIFICATE="$dev_ca/root.pem" \
  --from-file=HOSTS_CA_INTERMEDIATE_CERTIFICATE="$dev_ca/intermediate.pem" \
  --from-file=HOSTS_CA_INTERMEDIATE_PRIVATE_KEY="$dev_ca/intermediate.key" \
  --from-literal=IDENTITY_INVITECODEKEY="$(key invite-code.key)" \
  --from-literal=IDENTITY_ADMIN_TOTPKEY="$(key admin-totp.key)" \
  --from-literal=PAYOUTS_PAN_KEY="$(key pan.key)" \
  --from-literal=IMAGES_REGISTRYTOKEN_KEY="$(key registry-token.key)" \
  --from-literal=IMAGES_SCANNER_TOKEN="$(key scanner.token)"

# The local CA for the Gateway's certificate: made once and kept, so recreating the cluster doesn't change it (a
# browser told to trust it once keeps trusting it). Its name constraints permit only localhost and names under it
# (*.idleo.localhost among them; every *.localhost is this machine) and the loopback addresses, and its key usage only
# TLS servers, so trusting it can't make it vouch for any other site: a browser refuses a certificate it signed for
# anything else (smoke.sh checks both). One made before the constraints is made again. OpenSSL 3 (lib.sh): macOS's
# LibreSSL writes EC keys with explicit parameters, which Kubernetes refuses.
if [[ -s "$local_ca/ca.pem" ]] && ! openssl x509 -in "$local_ca/ca.pem" -noout -text 2>/dev/null | grep -q 'Name Constraints'; then
  log "making the local CA again, with name constraints (trust the new one, docs/runbooks/kubernetes.md)"
  rm -f "$local_ca/ca.pem" "$local_ca/ca.key"
  ca_remade=true
fi
if [[ ! -s "$local_ca/ca.pem" ]]; then
  openssl_bin="$(openssl3)"
  mkdir -p "$local_ca"
  umask 077
  "$openssl_bin" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve \
    -out "$local_ca/ca.key"
  "$openssl_bin" req -x509 -new -key "$local_ca/ca.key" -days 3650 -out "$local_ca/ca.pem" \
    -subj "/CN=IdleO local cluster CA" -addext "basicConstraints=critical,CA:true,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" -addext "extendedKeyUsage=serverAuth" \
    -addext "nameConstraints=critical,permitted;DNS:localhost,permitted;IP:127.0.0.0/255.0.0.0,permitted;IP:0:0:0:0:0:0:0:1/FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF"
fi
apply secret tls idleo-local-ca --cert="$local_ca/ca.pem" --key="$local_ca/ca.key"
# The Gateway's certificate from the old CA goes once the new one is in place, so cert-manager issues it again from it.
if [[ "${ca_remade:-}" == true ]]; then
  kubectl -n "$namespace" delete secret idleo-tls --ignore-not-found >/dev/null
fi

apply configmap idleo-development-catalog --from-file=development.yaml=templates/development.yaml
apply configmap idleo-sign-in --from-file=local-sign-in.json=control-plane/local-sign-in.json
log "secrets and development settings are in namespace $namespace"
