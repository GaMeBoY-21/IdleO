#!/usr/bin/env bash
# Runs only the suites the files changed since origin/main touch (committed, uncommitted and new), with check-all.sh's
# quiet output. The map from paths to suites is suites_for_path in scripts/lib/suites.sh, the one place for it.
#
#   scripts/check-changed.sh             run them
#   scripts/check-changed.sh --list      say which would run, and run none
#   scripts/check-changed.sh --verbose   print each suite's whole output as it runs
#   scripts/check-changed.sh --no-e2e    leave out the slow end-to-end suites (e2e, web-browser, drill, distro, images,
#                                        k8s)
#
# During work, run this. The full scripts/check-all.sh is for once, before pushing.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
# shellcheck source=scripts/lib/suites.sh
. "$root/scripts/lib/suites.sh"

list=false
skip_slow=false
for arg in "$@"; do
  case "$arg" in
    --list) list=true ;;
    --verbose) export IDLEO_VERBOSE=1 ;;
    --no-e2e) skip_slow=true ;;
    *) echo "check-changed: unknown option $arg" >&2; exit 2 ;;
  esac
done

base=origin/main
git rev-parse --verify -q "$base" >/dev/null || base=main
changed="$( { git diff --name-only "$(git merge-base "$base" HEAD)"; git ls-files --others --exclude-standard; } | sort -u)"

wanted=" "
while IFS= read -r file; do
  [[ -n "$file" ]] || continue
  for suite in $(suites_for_path "$file"); do
    [[ "$wanted" == *" $suite "* ]] || wanted="$wanted$suite "
  done
done <<<"$changed"

suites=()
for suite in "${ALL_SUITES[@]}"; do   # in check-all's order
  [[ "$wanted" == *" $suite "* ]] || continue
  if $skip_slow; then
    case "$suite" in e2e | web-browser | drill | distro | images | k8s) continue ;; esac
  fi
  suites+=("$suite")
done

if ((${#suites[@]} == 0)); then
  echo "check-changed: nothing changed since $base needs a suite"
  exit 0
fi
if $list; then
  printf 'check-changed: %s\n' "${suites[*]}"
  exit 0
fi
exec "$root/scripts/lib/run-suites.sh" "${suites[@]}"
