#!/usr/bin/env bash
# Every workflow's checks, on this machine, the same steps CI runs (scripts/checks/*.sh, and the e2e script): the
# gate while CI minutes are short, and before any push.
#
#   scripts/check-all.sh                 every suite, in order
#   scripts/check-all.sh web e2e         just these
#
# Suites: agent, cli, control-plane, proto, sdk, web (types, lint, unit tests, builds and size), web-browser
# (scripts/e2e/agent-online.sh --web: every screen and state, axe, layout shift, the journeys, Lighthouse and the
# performance spec), e2e (scripts/e2e/agent-online.sh, then scripts/e2e/start-check.sh). Each suite's output goes
# to logs/check-all/<suite>.log; the summary at the end says which passed and how long each took. It keeps going
# after a failure, so one run shows everything, and exits non-zero if anything failed.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
all=(agent cli control-plane proto sdk web web-browser e2e)
suites=("$@")
((${#suites[@]} > 0)) || suites=("${all[@]}")

# The pinned toolchains (CLAUDE.md, docs/runbooks/local-stack.md), found where a Mac keeps them.
for dir in /opt/homebrew/opt/openjdk@25/bin "$HOME/.cargo/bin" /opt/homebrew/opt/rustup/bin /opt/homebrew/opt/node@24/bin; do
  [[ -d "$dir" ]] && PATH="$dir:$PATH"
done
[[ -d /opt/homebrew/opt/openjdk@25 ]] && export JAVA_HOME=/opt/homebrew/opt/openjdk@25

logs="$root/logs/check-all"
mkdir -p "$logs"
results=()
failed=0
started_all=$SECONDS

run_suite() {
  case "$1" in
    agent | cli | control-plane | proto | sdk | web) "$root/scripts/checks/$1.sh" ;;
    web-browser) "$root/scripts/e2e/agent-online.sh" --web ;;
    e2e) "$root/scripts/e2e/agent-online.sh" && "$root/scripts/e2e/start-check.sh" ;;
    *)
      echo "check-all: no suite called $1; the suites are: ${all[*]}" >&2
      return 2
      ;;
  esac
}

for suite in "${suites[@]}"; do
  printf 'check-all: %s … ' "$suite"
  began=$SECONDS
  if run_suite "$suite" >"$logs/$suite.log" 2>&1; then
    outcome=pass
  else
    outcome=FAIL
    failed=1
  fi
  took=$((SECONDS - began))
  printf '%s (%dm%02ds)\n' "$outcome" $((took / 60)) $((took % 60))
  results+=("$(printf '%-12s %-4s %3dm%02ds  logs/check-all/%s.log' "$suite" "$outcome" $((took / 60)) $((took % 60)) "$suite")")
done

total=$((SECONDS - started_all))
printf '\ncheck-all summary (%s, commit %s)\n' "$(date '+%Y-%m-%d %H:%M')" "$(git rev-parse --short HEAD)"
printf '  %s\n' "${results[@]}"
printf '  %-12s %-4s %3dm%02ds\n' total "$( ((failed)) && echo FAIL || echo pass)" $((total / 60)) $((total % 60))
if [[ -n "$(git status --porcelain)" ]]; then
  printf '  note: the working tree has uncommitted changes:\n'
  git status --short | sed 's/^/    /'
fi
exit "$failed"
