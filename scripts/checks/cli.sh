#!/usr/bin/env bash
# The renter CLI's checks (.github/workflows/cli.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
cd "$(dirname "$0")/../../cli"
# build: format, lint and test (each OS in CI); deny: licences and advisories (CI runs it through cargo-deny's
# action); all, the default: both.
part="${1:-all}"
if [[ "$part" == build || "$part" == all ]]; then
  cargo fmt --all --check
  cargo clippy --workspace --all-targets --locked -- -D warnings
  cargo test --workspace --locked
fi
if [[ "$part" == deny || "$part" == all ]]; then
  cargo deny --all-features check
fi
