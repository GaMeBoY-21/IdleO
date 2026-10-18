#!/usr/bin/env bash
# Authenticode-signs the Windows app's programs in place (ADR 0034): the one place it happens. publish.sh calls it
# before anything is hashed, so the signed files are the ones the manifest names.
#
#   scripts/release/sign-windows.sh dist/windows
#
# TODO(signing): needs the code-signing certificate (host experience §8, decision 1). With IDLEO_SIGN_CERT (the
# certificate chain, PEM) and IDLEO_SIGN_KEY (its private key, PEM) set, which Nikki sets and nothing else reads, each
# program is signed with osslsigncode and a timestamp. Without them the programs are left unsigned, loudly.
set -euo pipefail
dir="${1:?usage: scripts/release/sign-windows.sh <folder of .exe files>}"
if [[ -z "${IDLEO_SIGN_CERT:-}" || -z "${IDLEO_SIGN_KEY:-}" ]]; then
  echo "sign-windows: ##############################################################" >&2
  echo "sign-windows: Unsigned: Windows SmartScreen will warn (IDLEO_SIGN_CERT and IDLEO_SIGN_KEY aren't set)" >&2
  echo "sign-windows: ##############################################################" >&2
  exit 0
fi
command -v osslsigncode >/dev/null || { echo "sign-windows: osslsigncode isn't installed: brew install osslsigncode" >&2; exit 1; }
for exe in "$dir"/*.exe; do
  osslsigncode sign -certs "$IDLEO_SIGN_CERT" -key "$IDLEO_SIGN_KEY" -n "IdleO" -h sha256 \
    -t http://timestamp.digicert.com -in "$exe" -out "$exe.signed"
  mv "$exe.signed" "$exe"
  echo "sign-windows: signed $(basename "$exe")"
done
