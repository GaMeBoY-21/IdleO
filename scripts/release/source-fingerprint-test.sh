#!/usr/bin/env bash
# Checks source-fingerprint.sh in a scratch repository: the same files give the same fingerprint, and a changed file, a
# new file, a new Linux image, other addresses and the failing-trial switch each change it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/scripts/release" "$work/agent/distro" "$work/proto" "$work/dist/distro" "$work/private/release-dev"
cp "$here/source-fingerprint.sh" "$work/scripts/release/"
echo 0.1.0 >"$work/agent/distro/VERSION"
echo 'fn main() {}' >"$work/agent/main.rs"
echo 'syntax = "proto3";' >"$work/proto/a.proto"
echo 'IDLEO_SETUP_API_URL=http://10.0.0.1:8080' >"$work/private/release-dev/setup.env"
echo 'aaa  idleo-distro-0.1.0.tar.gz' >"$work/dist/distro/idleo-distro-0.1.0.tar.gz.sha256"
(cd "$work" && git init -q && git add agent proto && git -c user.name=t -c user.email=t@t commit -qm base)
fingerprint() { "$work/scripts/release/source-fingerprint.sh"; }
fail() { echo "source-fingerprint-test: $1" >&2; exit 1; }
differs() { [[ "$(fingerprint)" != "$base" ]] || fail "$1 didn't change the fingerprint"; }
base="$(fingerprint)"
[[ "$base" =~ ^[0-9a-f]{64}$ ]] || fail "not a SHA-256: $base"
[[ "$(fingerprint)" == "$base" ]] || fail "the same files gave two fingerprints"
echo 'fn main() { run(); }' >"$work/agent/main.rs"; differs "an edited committed file"
echo 'fn main() {}' >"$work/agent/main.rs"; [[ "$(fingerprint)" == "$base" ]] || fail "undoing an edit didn't restore it"
echo 'x' >"$work/agent/new.rs"; differs "a new, uncommitted file"
rm "$work/agent/new.rs"
echo 'bbb  idleo-distro-0.1.0.tar.gz' >"$work/dist/distro/idleo-distro-0.1.0.tar.gz.sha256"; differs "a new Linux image"
echo 'aaa  idleo-distro-0.1.0.tar.gz' >"$work/dist/distro/idleo-distro-0.1.0.tar.gz.sha256"
echo 'IDLEO_SETUP_API_URL=http://10.0.0.2:8080' >"$work/private/release-dev/setup.env"; differs "another address"
echo 'IDLEO_SETUP_API_URL=http://10.0.0.1:8080' >"$work/private/release-dev/setup.env"
[[ "$(IDLEO_TEST_FAIL_TRIAL=1 fingerprint)" != "$base" ]] || fail "IDLEO_TEST_FAIL_TRIAL didn't change it"
rm "$work/agent/main.rs"; differs "a deleted file"
echo "source-fingerprint-test: PASS"
