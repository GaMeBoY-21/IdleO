#!/usr/bin/env bash
# The host agent's checks (.github/workflows/agent.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
cd "$(dirname "$0")/../../agent"
# build: format, lint and test (each OS in CI), then the Windows lint from this machine when cargo-xwin is there; deny:
# licences and advisories, for every target and for the Windows app alone (CI runs them through cargo-deny's action);
# all, the default: both.
part="${1:-all}"
if [[ "$part" == build || "$part" == all ]]; then
  cargo fmt --all --check
  cargo clippy --workspace --all-targets --locked -- -D warnings
  cargo test --workspace --locked
  # The Windows app's own code (the tray, the setup window, the launcher's dialogs) builds only for Windows, so it is
  # linted for Windows too: with cargo-xwin and LLVM on a Mac or Linux, natively on Windows.
  if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
    : # CI's Windows job lints natively above.
  elif command -v cargo-xwin >/dev/null && command -v llvm-rc >/dev/null; then
    XWIN_ACCEPT_LICENSE=1 cargo xwin clippy --target x86_64-pc-windows-msvc --workspace --all-targets --locked -- -D warnings
  else
    echo "agent checks: SKIPPED the Windows lint: cargo-xwin or llvm-rc isn't installed (cargo install --locked cargo-xwin; brew install llvm lld)"
  fi
fi
if [[ "$part" == deny || "$part" == all ]]; then
  cargo deny check
  cargo deny --config deny-windows.toml check
fi
