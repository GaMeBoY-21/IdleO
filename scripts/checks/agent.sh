#!/usr/bin/env bash
# The host agent's checks (.github/workflows/agent.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry agent "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../../agent"
# build: format, lint and test (each OS in CI), then the Windows lint from this machine when cargo-xwin is there; deny:
# licences and advisories, for every target and for the Windows app alone (CI runs them through cargo-deny's action);
# all, the default: both.
part="${1:-all}"
if [[ "$part" == build || "$part" == all ]]; then
  # The repository is public: no real name, machine, serial or address may come back into a tracked file. The strings
  # are the left side of private/go-public-replacements.txt (git-ignored: a public list of them would be the leak), so
  # this runs where that file is and says so where it isn't.
  personal=../private/go-public-replacements.txt
  if [[ -f "$personal" ]]; then
    grep_args=()
    while IFS= read -r string; do grep_args+=(-e "$string"); done < <(sed 's/==>.*//' "$personal")
    if git grep -nIF "${grep_args[@]}" -- ..; then
      echo "agent checks: a personal string from private/go-public-replacements.txt is back in a tracked file (above)" >&2
      exit 1
    fi
  else
    echo "agent checks: SKIPPED the personal-string check: private/go-public-replacements.txt isn't here"
  fi
  ../scripts/release/source-fingerprint-test.sh
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
