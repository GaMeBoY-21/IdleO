#!/usr/bin/env bash
# Rewrites the repository's history for going public, in a fresh clone. It never touches the repository it is started
# from. In every commit it:
#   - removes the private material (docs/plan, docs/prompts, phase0/RESULTS.md and every PDF; they live in idleo-internal);
#   - scrubs the strings in private/go-public-replacements.txt (real name, machine, serial, addresses; git-ignored);
#   - makes every author and committer the same name with the GitHub no-reply address ID+LOGIN@users.noreply.github.com.
# Fill in ID, run it, then run scripts/go-public/verify.sh on the clone and follow the commands this script prints.
#
#   scripts/go-public/rewrite.sh [SOURCE_REPO [CLONE_DIR]]
#
# SOURCE_REPO defaults to this repository, CLONE_DIR to ~/idleo-public. GO_PUBLIC_ID overrides ID, for testing the script
# on a throwaway clone with a dummy number.
set -euo pipefail

ID="${GO_PUBLIC_ID:-174181445}" # your GitHub user id: gh api user --jq .id
LOGIN=GaMeBoY-21

here="$(cd "$(dirname "$0")" && pwd)"
source_repo="${1:-$(git -C "$here" rev-parse --show-toplevel)}"
clone="${2:-$HOME/idleo-public}"
replacements="${GO_PUBLIC_REPLACEMENTS:-$here/../../private/go-public-replacements.txt}"

[[ -f "$replacements" ]] || { echo "$replacements is missing: the list of personal strings (git-ignored)" >&2; exit 1; }
command -v git-filter-repo >/dev/null || { echo "git-filter-repo isn't installed (brew install git-filter-repo)" >&2; exit 1; }
[[ "$ID" =~ ^[0-9]+$ ]] || { echo "ID must be the number from: gh api user --jq .id" >&2; exit 1; }
[[ ! -e "$clone" ]] || { echo "$clone already exists; remove it or pass another folder" >&2; exit 1; }
[[ -z "$(git -C "$source_repo" status --porcelain)" ]] || { echo "$source_repo has uncommitted changes; commit them first, the rewrite starts from the commits" >&2; exit 1; }

origin="$(git -C "$source_repo" remote get-url origin)"
name="$(git -C "$source_repo" log -1 --format=%an)"
noreply="$ID+$LOGIN@users.noreply.github.com"

git clone --quiet --no-local "$source_repo" "$clone"
echo "cloned $(git -C "$clone" rev-list --all --count) commits into $clone"

# One mailmap line for every address that ever signed a commit, so none is left behind.
mailmap="$(mktemp)"
trap 'rm -f "$mailmap"' EXIT
git -C "$clone" log --all --format='%ae%n%ce' | sort -u | while IFS= read -r old; do
  [[ "$old" == "$noreply" ]] || echo "$name <$noreply> <$old>"
done >"$mailmap"

(
  cd "$clone"
  git filter-repo --quiet \
    --invert-paths \
    --path docs/plan --path docs/prompts --path phase0/RESULTS.md --path-glob '*.pdf' \
    --replace-text "$replacements" \
    --mailmap "$mailmap"
  # filter-repo drops the remote so a rewritten clone can't be pushed by accident; put it back for the one push.
  git remote add origin "$origin"
)

echo
echo "rewritten: $(git -C "$clone" rev-list --all --count) commits in $clone (the original repository is untouched)"
echo "next:"
echo "  scripts/go-public/verify.sh $clone"
echo "  git -C $clone push --force origin main     # only after verify.sh says PASS"
