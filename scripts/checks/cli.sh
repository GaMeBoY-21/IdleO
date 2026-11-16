#!/usr/bin/env bash
# The renter CLI's checks (.github/workflows/cli.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry cli "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../../cli"
# build: format, lint and test (each OS in CI); deny: licences and advisories (CI runs it through cargo-deny's
# action); all, the default: both.
part="${1:-all}"
# --fast (scripts/check-changed.sh): format, lint and the unit tests only, no licence check, image or Windows lint.
[[ -n "${IDLEO_FAST:-}" ]] && part=build
if [[ "$part" == build || "$part" == all ]]; then
  cargo fmt --all --check
  cargo clippy --workspace --all-targets --locked -- -D warnings
  cargo test --workspace --locked ${IDLEO_FAST:+--lib --bins}
fi
if [[ "$part" == deny || "$part" == all ]]; then
  cargo deny --all-features check
fi
