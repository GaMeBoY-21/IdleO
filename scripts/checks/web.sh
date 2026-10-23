#!/usr/bin/env bash
# The web app's checks without a browser: the audit, types, lint, unit tests, a production build with the size
# budget, and a production build refusing the local sign-in adapter (.github/workflows/web.yml's checks job runs
# this; so does scripts/check-all.sh). The browser checks are scripts/e2e/agent-online.sh --web.
set -euo pipefail
cd "$(dirname "$0")/../../web"
pnpm install --frozen-lockfile
pnpm audit --audit-level high
pnpm typecheck
pnpm lint
pnpm test
# The app and the website are two builds with two outputs, each told the other's address (ADR 0029).
production_env=(IDLEO_DEPLOYMENT=production IDLEO_SITE_URL=https://site.example.invalid IDLEO_APP_URL=https://app.example.invalid
  VITE_API_URL=https://api.example.invalid VITE_STORAGE_URL=https://storage.example.invalid VITE_AUTH=firebase
  VITE_FIREBASE_API_KEY=placeholder-not-a-key VITE_FIREBASE_AUTH_DOMAIN=placeholder.firebaseapp.com
  VITE_FIREBASE_PROJECT_ID=placeholder VITE_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js)
env "${production_env[@]}" pnpm build
env "${production_env[@]}" pnpm build:site
pnpm size
refused="$(mktemp -d)"
if IDLEO_DEPLOYMENT=production VITE_API_URL=https://api.example.invalid VITE_STORAGE_URL=https://storage.example.invalid \
  VITE_AUTH=oidc-mock VITE_OIDC_ISSUER=https://idp.example.invalid VITE_OIDC_CLIENT_ID=idleo \
  pnpm exec vite build --outDir "$refused/dist" >"$refused/build.log" 2>&1; then
  echo "web: a production build accepted the oidc-mock adapter"; exit 1
fi
grep -q "can't use the oidc-mock sign-in adapter" "$refused/build.log"
# A deployed build without the app's address is refused, by the app's build and by the website's.
without_app=()
for setting in "${production_env[@]}"; do [[ $setting == IDLEO_APP_URL=* ]] || without_app+=("$setting"); done
if env "${without_app[@]}" pnpm exec vite build --outDir "$refused/dist-app" >"$refused/app.log" 2>&1; then
  echo "web: a production build accepted a missing IDLEO_APP_URL"; exit 1
fi
grep -q "needs IDLEO_APP_URL" "$refused/app.log"
if env "${without_app[@]}" IDLEO_WEBSITE_DIST="$refused/dist-website" node scripts/build-site.ts >"$refused/site.log" 2>&1; then
  echo "web: the website's build accepted a missing IDLEO_APP_URL"; exit 1
fi
grep -q "needs IDLEO_APP_URL" "$refused/site.log"
rm -rf "$refused"
