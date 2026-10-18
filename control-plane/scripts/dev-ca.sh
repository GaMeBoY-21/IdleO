#!/usr/bin/env bash
# Creates a throwaway development PKI under the gitignored private/dev-ca/:
# a root, an intermediate signed by it, and a localhost server certificate for
# the gRPC port, all ECDSA P-256, shaped like production (ADR 0010). For local
# runs only; production's root is made by hand (docs/runbooks/certificate-authority.md).
#
#   scripts/dev-ca.sh            create it, if it doesn't exist yet
#   scripts/dev-ca.sh --force    throw it away and create a new one
#   scripts/dev-ca.sh --server-ip 192.168.1.20
#                                also name that address in the server certificate (./start.sh --lan): only the server
#                                certificate is issued again, so enrolled agents keep trusting the root
set -euo pipefail

cd "$(dirname "$0")/../.."
out=private/dev-ca
force=false
server_ip=""
while (($# > 0)); do
  case "$1" in
    --force) force=true ;;
    --server-ip) server_ip="${2:?--server-ip needs an address}"; shift ;;
    *) echo "dev-ca: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

if ! git check-ignore -q "$out/probe"; then
  echo "dev-ca: refusing to write keys: $out/ is not gitignored." >&2
  exit 1
fi

openssl_bin="${OPENSSL:-}"
if [[ -z "$openssl_bin" ]]; then
  for candidate in /opt/homebrew/opt/openssl@3/bin/openssl /usr/local/opt/openssl@3/bin/openssl openssl; do
    if command -v "$candidate" >/dev/null && "$candidate" version | grep -q '^OpenSSL 3'; then
      openssl_bin="$candidate"
      break
    fi
  done
fi
if [[ -z "$openssl_bin" ]]; then
  echo "dev-ca: needs OpenSSL 3 (macOS ships LibreSSL): brew install openssl@3, or set OPENSSL=/path/to/openssl." >&2
  exit 1
fi

# The admin console's TOTP key (ADR 0027) and the HMAC key for invite and recovery codes (ADR 0024), beside the CA:
# kept across --force, since replacing them makes every admin's enrolment unreadable and every unused code worthless.
make_admin_totp_key() {
  local name
  for name in admin-totp.key invite-code.key; do
    if [[ ! -s "$out/$name" ]]; then
      (umask 077 && mkdir -p "$out" && "$openssl_bin" rand -base64 32 > "$out/$name")
    fi
  done
}

key() {
  "$openssl_bin" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1"
}
serial() {
  echo "0x$("$openssl_bin" rand -hex 16)"
}

# Server: the gRPC port's certificate for localhost (and the --server-ip address), serverAuth only, 90 days.
issue_server() {
  local names="DNS:localhost" work
  [[ -n "$server_ip" ]] && names="$names,IP:$server_ip"
  work="$(mktemp -d)"
  key "$out/server.key"
  printf 'basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n' "$names" > "$work/server.ext"
  "$openssl_bin" req -new -key "$out/server.key" -subj "/CN=localhost" -out "$work/server.csr"
  "$openssl_bin" x509 -req -in "$work/server.csr" -CA "$out/intermediate.pem" -CAkey "$out/intermediate.key" -days 90 -sha256 \
    -set_serial "$(serial)" -extfile "$work/server.ext" -out "$out/server.pem"
  cat "$out/server.pem" "$out/intermediate.pem" > "$out/server-chain.pem"
  rm -rf "$work"
  "$openssl_bin" verify -CAfile "$out/root.pem" -untrusted "$out/intermediate.pem" "$out/server.pem" >/dev/null
}

if [[ -e "$out/root.pem" ]] && ! $force; then
  make_admin_totp_key
  if [[ -n "$server_ip" ]] && ! "$openssl_bin" x509 -in "$out/server.pem" -noout -ext subjectAltName 2>/dev/null |
    grep -q "IP Address:$server_ip\(,\|$\)"; then
    (umask 077 && issue_server)
    echo "dev-ca: issued the server certificate again, for localhost and $server_ip"
    exit 0
  fi
  echo "dev-ca: $out already exists; scripts/dev-ca.sh --force replaces it."
  exit 0
fi

umask 077
mkdir -p "$out"
find "$out" -mindepth 1 ! -name admin-totp.key ! -name invite-code.key -delete
make_admin_totp_key
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Root: self-signed, 10 years, may sign one level of CA below it.
key "$out/root.key"
printf 'basicConstraints=critical,CA:true,pathlen:1\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n' > "$work/root.ext"
"$openssl_bin" req -new -key "$out/root.key" -subj "/CN=IdleO Development Root CA" -out "$work/root.csr"
"$openssl_bin" x509 -req -in "$work/root.csr" -key "$out/root.key" -days 3650 -sha256 \
  -set_serial "$(serial)" -extfile "$work/root.ext" -out "$out/root.pem"

# Intermediate: signed by the root, 1 year, signs host and server certificates.
key "$out/intermediate.key"
printf 'basicConstraints=critical,CA:true,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n' > "$work/intermediate.ext"
"$openssl_bin" req -new -key "$out/intermediate.key" -subj "/CN=IdleO Development Intermediate CA" -out "$work/intermediate.csr"
"$openssl_bin" x509 -req -in "$work/intermediate.csr" -CA "$out/root.pem" -CAkey "$out/root.key" -days 365 -sha256 \
  -set_serial "$(serial)" -extfile "$work/intermediate.ext" -out "$out/intermediate.pem"

issue_server
echo "dev-ca: created $out/ (root, intermediate, localhost server certificate, the admin TOTP key). Never commit it."
