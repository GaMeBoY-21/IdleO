#!/usr/bin/env bash
# Opens the pull request that deploys a release (ADR 0046), from a workflow, as the deploy app:
# deploy/clusters/<env>/idleo/release.yaml, already written (scripts/deploy/release.py), committed on a branch of its
# own and nothing else with it. An older open pull request for the same environment is closed as superseded.
#
#   GH_TOKEN=<the deploy app's token> DEPLOY_APP_SLUG=<its slug> \
#     scripts/deploy/open-pull-request.sh <env> <branch> <title> <body file> [--auto-merge]
#
# The deploy app's token (actions/create-github-app-token, in the images and promote workflows) can write contents
# and pull requests on this repository and nothing else. A pull request it opens starts its own required checks, as a
# person's does (one opened with GitHub Actions' own token would start none). With --auto-merge, GitHub merges it by
# rebase once they pass; without, a person reviews and merges it.
set -euo pipefail
cd "$(dirname "$0")/../.."
fail() { printf 'deploy: %s\n' "$*" >&2; exit 1; }
[[ $# -ge 4 ]] || fail "usage: $0 <env> <branch> <title> <body file> [--auto-merge]"
[[ -n "${DEPLOY_APP_SLUG:-}" ]] || fail "DEPLOY_APP_SLUG isn't set: run this with the deploy app's token"
environment="$1" branch="$2" title="$3" body="$4"
auto_merge=false
[[ "${5:-}" == --auto-merge ]] && auto_merge=true
release="deploy/clusters/$environment/idleo/release.yaml"

if git diff --quiet -- "$release"; then
  echo "deploy: $environment already runs this release; nothing to open"
  exit 0
fi
# The app's own commit identity, as GitHub shows its bot user.
bot="$DEPLOY_APP_SLUG[bot]"
git config user.name "$bot"
git config user.email "$(gh api "/users/$bot" --jq .id)+$bot@users.noreply.github.com"
git switch -c "$branch"
git add "$release"
git commit -q -m "$(tr '[:upper:]' '[:lower:]' <<<"${title:0:1}")${title:1}"
# The branch is this script's alone: a re-run of a failed job replaces what the last attempt pushed.
git push -q --force origin "$branch"

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
