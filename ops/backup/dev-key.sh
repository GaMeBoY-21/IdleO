#!/usr/bin/env bash
# The development backup key (ADR 0036): an OpenPGP key pair in the gitignored private/backup-dev/, made once (--force
# makes a new one, which makes every earlier development backup unreadable). The database's container gets only the
# public half (backup.pub.asc), which encrypts; restoring reads the private half (backup.key.asc). Made with GnuPG
# inside the database's own image, so the Mac needs nothing more. Production's key is made the same way on a machine
# that keeps the private half offline: docs/runbooks/backups.md "The backup key".
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
out="$root/private/backup-dev"
force=false
[[ "${1:-}" == --force ]] && force=true
if [[ -s "$out/backup.pub.asc" && -s "$out/backup.key.asc" ]] && ! $force; then
  echo "dev-key: private/backup-dev/ already has a key (--force makes a new one)"
  exit 0
fi
mkdir -p "$out"
chmod 700 "$out"
image="$("$root/ops/backup/image.sh")"
docker run --rm -v "$out:/out" --entrypoint bash "$image" -c '
  set -euo pipefail
  export GNUPGHOME="$(mktemp -d)"
  gpg --batch --quiet --pinentry-mode loopback --passphrase "" \
    --quick-generate-key "IdleO development backups <backups@idleo.test>" rsa4096 encrypt never
  gpg --batch --armor --export > /out/backup.pub.asc
  gpg --batch --armor --pinentry-mode loopback --passphrase "" --export-secret-keys > /out/backup.key.asc
  chmod 600 /out/backup.key.asc
'
echo "dev-key: made private/backup-dev/backup.pub.asc (the database encrypts with it) and backup.key.asc (restores read it)"
