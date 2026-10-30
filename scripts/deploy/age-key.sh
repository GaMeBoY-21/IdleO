#!/usr/bin/env bash
# An environment's age key pair for SOPS (ADR 0046; docs/runbooks/deploying.md, "Secrets"). The public half is the
# recipient .sops.yaml encrypts deploy/clusters/<env>/secrets/ for; the private half is the only key that decrypts
# them, and Flux holds it in the cluster as the Secret flux-system/sops-age.
#
#   scripts/deploy/age-key.sh local
#       The Mac's cluster's key, kept in private/k8s/age/local.agekey (made once; up.sh and CI use it).
#   scripts/deploy/age-key.sh staging|production
#       Made by the product owner on her own machine, into private/age/<env>.agekey: she puts the file's contents in
#       her password manager, loads it into the cluster (the runbook's command), and deletes the file.
#   scripts/deploy/age-key.sh <env> --rotate
#       A new key in place of the old one: every Secret file of that environment is encrypted again for the new key
#       (sops updatekeys, with the old key to read them); the old key is kept beside it as <file>.old until the cluster
#       has the new one. After a leak, also change every secret value (the runbook says how).
#
# It writes the public half into .sops.yaml and prints it. It never prints a private key.
set -euo pipefail
cd "$(dirname "$0")/../.."
fail() { printf 'age-key: %s\n' "$*" >&2; exit 1; }
env="${1:-}"
rotate=false
[[ "${2:-}" == --rotate ]] && rotate=true
case "$env" in
  local) key_file=private/k8s/age/local.agekey ;;
  staging | production) key_file="private/age/$env.agekey" ;;
  *) fail "usage: $0 local|staging|production [--rotate]" ;;
esac
for tool in age-keygen sops; do
  command -v "$tool" >/dev/null || fail "$tool isn't installed (macOS: brew install age sops)"
done
git check-ignore -q "$key_file" || fail "refusing to write $key_file: it isn't gitignored"

if [[ -s "$key_file" && "$rotate" == true ]]; then
  mv "$key_file" "$key_file.old"
fi
if [[ ! -s "$key_file" ]]; then
  mkdir -p "$(dirname "$key_file")"
  (umask 077 && age-keygen -o "$key_file" 2>/dev/null)
fi
public="$(age-keygen -y "$key_file")"

# The environment's rule in .sops.yaml: its secrets/ folder, data and stringData encrypted for this recipient.
python3 - "$env" "$public" <<'PY'
import re, sys
env, public = sys.argv[1:]
path = ".sops.yaml"
text = open(path).read()
rule = (f"  - path_regex: deploy/clusters/{env}/secrets/.*\\.yaml$\n"
        f"    encrypted_regex: ^(data|stringData)$\n"
        f"    age: {public}\n")
pattern = re.compile(rf"  - path_regex: deploy/clusters/{env}/secrets/.*?\n    age: age1[0-9a-z]+\n", re.S)
text = pattern.sub(lambda _: rule, text) if pattern.search(text) else text.rstrip("\n") + "\n" + rule
open(path, "w").write(text)
PY

if [[ "$rotate" == true && -s "$key_file.old" ]]; then
  for file in deploy/clusters/"$env"/secrets/*/*.yaml; do
    [[ "$(basename "$file")" == kustomization.yaml ]] && continue
    SOPS_AGE_KEY_FILE="$key_file.old" sops updatekeys --yes "$file" >/dev/null
  done
  echo "age-key: $env's Secret files are encrypted for the new key; commit them with .sops.yaml"
fi
echo "age-key: $env's public key (in .sops.yaml): $public"
echo "age-key: its private key is $key_file (never commit it, never paste it anywhere but the password manager)"
