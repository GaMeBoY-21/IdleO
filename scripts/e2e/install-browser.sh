#!/usr/bin/env bash
# Installs the web app's dependencies and Playwright's Chromium with its system libraries, for the browser suites in CI.
# The download usually takes 25 s; once it stalled for 13 minutes and ran an e2e shard out of time, so a stalled try is
# cut off after five minutes and made again, three tries in all.
set -euo pipefail
cd "$(dirname "$0")/../../web"
pnpm install --frozen-lockfile
for attempt in 1 2 3; do
  if timeout 300 pnpm exec playwright install --with-deps chromium; then
    exit 0
  fi
  echo "install-browser: try $attempt of 3 failed or stalled" >&2
done
exit 1
