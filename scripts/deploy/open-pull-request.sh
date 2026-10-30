#!/usr/bin/env bash
# Opens the pull request that deploys a release (ADR 0046), from a workflow, with GitHub Actions' own token:
# deploy/clusters/<env>/idleo/release.yaml, already written (scripts/deploy/release.py), committed on a branch of its
# own and nothing else with it. An older open pull request for the same environment is closed as superseded.
#
#   scripts/deploy/open-pull-request.sh <env> <branch> <title> <body file> [--auto-merge]
#
# A pull request opened with that token starts no workflow by itself (GitHub's guard against loops), so the required
# checks (ADR 0044) are started on its branch by hand (workflow_dispatch): each reports on the branch's commit as a
# pull request's own run would, and the ruleset counts them. With --auto-merge, GitHub merges it by rebase once they
# pass; without, a person reviews and merges it.
set -euo pipefail
cd "$(dirname "$0")/../.."
fail() { printf 'deploy: %s\n' "$*" >&2; exit 1; }
[[ $# -ge 4 ]] || fail "usage: $0 <env> <branch> <title> <body file> [--auto-merge]"
environment="$1" branch="$2" title="$3" body="$4"
auto_merge=false
[[ "${5:-}" == --auto-merge ]] && auto_merge=true
release="deploy/clusters/$environment/idleo/release.yaml"
# The workflows that hold the required checks (ADR 0044's list).
required=(agent cli codeql control-plane deploy gitleaks images proto scanner sdk web)

if git diff --quiet -- "$release"; then
  echo "deploy: $environment already runs this release; nothing to open"
  exit 0
fi
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git switch -c "$branch"
git add "$release"
git commit -q -m "$(tr '[:upper:]' '[:lower:]' <<<"${title:0:1}")${title:1}"
git push -q origin "$branch"

url="$(gh pr create --base main --head "$branch" --title "$title" --body-file "$body")"
echo "deploy: opened $url"
for open in $(gh pr list --state open --json number,headRefName \
  --jq ".[] | select(.headRefName != \"$branch\" and (.headRefName | startswith(\"deploy/$environment-\"))) | .number"); do
  gh pr close "$open" --delete-branch --comment "Superseded by $url." >/dev/null
  echo "deploy: closed #$open, superseded"
done
if [[ "$auto_merge" == true ]]; then
  gh pr merge "$url" --auto --rebase --delete-branch
fi
for workflow in "${required[@]}"; do
  gh workflow run "$workflow.yml" --ref "$branch"
done
echo "deploy: started the required checks on $branch"
