#!/usr/bin/env bash
# The image scanner's checks (.github/workflows/scanner.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry scanner "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../../scanner"
# build: format, lint and test; deny: licences and advisories (CI runs it through cargo-deny's action); image: the
# container it ships in, built and run once, read-only and without capabilities, to print its version; all, the default.
part="${1:-all}"
if [[ "$part" == build || "$part" == all ]]; then
  cargo fmt --all --check
  cargo clippy --all-targets --all-features --locked -- -D warnings
  cargo test --all-features --locked
fi
if [[ "$part" == deny || "$part" == all ]]; then
  cargo deny --all-features check
fi
if [[ "$part" == image || "$part" == all ]]; then
  docker build -q -t idleo-scanner:check . >/dev/null
  docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges idleo-scanner:check --version
  [[ "$(docker run --rm --entrypoint id idleo-scanner:check -u)" == 10001 ]] || { echo "scanner: the image doesn't run as 10001"; exit 1; }
fi
