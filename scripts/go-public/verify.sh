#!/usr/bin/env bash
# Checks a clone made by rewrite.sh before it is pushed: no secrets anywhere in its history, none of the removed
# paths, none of the personal strings, and no address but the GitHub no-reply one on any commit. Prints PASS or FAIL.
#
#   scripts/go-public/verify.sh [CLONE_DIR]      (default ~/idleo-public; needs Docker for gitleaks)
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
clone="${1:-$HOME/idleo-public}"
failed=0
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
fail() { echo "FAIL: $*"; failed=1; }

[[ -d "$clone/.git" ]] || { echo "$clone isn't a clone" >&2; exit 2; }
echo "commits: $(git -C "$clone" rev-list --all --count)"

moved="$(git -C "$clone" log --all --oneline -- docs/plan docs/prompts phase0/RESULTS.md ':(glob)**/*.pdf')"
[[ -z "$moved" ]] && echo "ok: no commit touches docs/plan, docs/prompts, phase0/RESULTS.md or a PDF" || fail "removed paths are still in history:
$moved"
git -C "$clone" cat-file -e HEAD:docs/reference/personal-data-map.md 2>/dev/null \
  && echo "ok: docs/reference/personal-data-map.md is kept" || fail "docs/reference/personal-data-map.md is missing"

strings="$(python3 -I "$here/scan-history.py" "$clone" "${GO_PUBLIC_REPLACEMENTS:-$here/../../private/go-public-replacements.txt}")" \
  && echo "ok: no personal string in any file version" || fail "personal strings in history (blob path string):
$strings"

emails="$(git -C "$clone" log --all --format='%ae%n%ce' | sort -u)"
if [[ -n "$emails" && -z "$(grep -v '@users\.noreply\.github\.com$' <<<"$emails")" ]]; then
  echo "ok: every author and committer is $(head -1 <<<"$emails")"
else
  fail "commit addresses other than the no-reply one:
$emails"
fi
mail="$(git -C "$clone" log --all --format='%an%n%cn%n%B' | grep -iE 'gmail|@[a-z0-9-]+\.[a-z]{2,}' | grep -v '@users\.noreply\.github\.com' || true)"
[[ -z "$mail" ]] && echo "ok: no other address in a commit message or name" || fail "addresses in commit messages:
$mail"

# Needs the repo's own .gitleaks.toml, which lists the fake test keys; --exit-code 1 makes any other finding fail.
if docker run --rm -v "$clone":/repo:ro zricethezav/gitleaks:latest git /repo --log-opts=--all --redact --no-banner --exit-code 1 >"$log" 2>&1; then
  echo "ok: gitleaks found no secret in $(git -C "$clone" rev-list --all --count) commits"
else
  fail "gitleaks found something:"
  tail -20 "$log"
fi

echo
[[ $failed == 0 ]] && echo "PASS" || { echo "FAIL"; exit 1; }
