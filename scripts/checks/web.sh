#!/usr/bin/env bash
# The web app's checks without a browser: the audit, types, lint, unit tests, a production build with the size
# budget, and a production build refusing the local sign-in adapter (.github/workflows/web.yml's checks job runs
# this; so does scripts/check-all.sh). The browser checks are scripts/e2e/agent-online.sh --web.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry web "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../../web"
pnpm install --frozen-lockfile
pnpm audit --audit-level high
pnpm typecheck
pnpm lint
pnpm test
# The app's sites and the website are builds with their own outputs, each told the others' addresses (ADR 0029, ADR 0058).
production_env=(IDLEO_DEPLOYMENT=production IDLEO_SITE_URL=https://site.example.invalid IDLEO_APP_URL=https://app.example.invalid
  IDLEO_HOST_URL=https://host.example.invalid IDLEO_ADMIN_URL=https://admin.example.invalid
  VITE_API_URL=https://api.example.invalid VITE_STORAGE_URL=https://storage.example.invalid VITE_AUTH=firebase
  VITE_FIREBASE_API_KEY=placeholder-not-a-key VITE_FIREBASE_AUTH_DOMAIN=placeholder.firebaseapp.com
  VITE_FIREBASE_PROJECT_ID=placeholder VITE_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js)
env "${production_env[@]}" pnpm build
env "${production_env[@]}" pnpm build:site
pnpm size
# Each site's own security policy (ADR 0058): its API in connect-src, Razorpay's Checkout only on the renter app's Credit
# page, and the host site and the admin site, which take no payment, naming Razorpay nowhere.
for dist in dist dist-host dist-admin; do
  grep -q 'connect-src[^;]*https://api.example.invalid' "$dist/headers.json" ||
    { echo "web: $dist's policy doesn't allow its API"; exit 1; }
done
grep -q 'checkout.razorpay.com' dist/path-headers.json
if grep -q 'razorpay' dist-host/headers.json dist-host/path-headers.json dist-admin/headers.json dist-admin/path-headers.json dist/headers.json; then
  echo "web: Razorpay's Checkout is allowed beyond the renter app's Credit page"; exit 1
fi
# The console is the admin site's alone: the renter app sends its old addresses there, and has no page of its own for one.
grep -q '"/admin/\*": "https://admin.example.invalid/admin/\*"' dist/redirects.json ||
  { echo "web: the renter app doesn't send /admin/ to the admin site"; exit 1; }
if [[ -e dist/admin ]]; then
  echo "web: the renter app still has pages under /admin/"; exit 1
fi
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
# And without the host site's, by the host site's build and the website's.
without_host=()
for setting in "${production_env[@]}"; do [[ $setting == IDLEO_HOST_URL=* ]] || without_host+=("$setting"); done
if env "${without_host[@]}" IDLEO_WEB_SITE=host pnpm exec vite build --outDir "$refused/dist-host" >"$refused/host.log" 2>&1; then
  echo "web: a production build of the host site accepted a missing IDLEO_HOST_URL"; exit 1
fi
grep -q "needs IDLEO_HOST_URL" "$refused/host.log"
if env "${without_host[@]}" IDLEO_WEBSITE_DIST="$refused/dist-website" node scripts/build-site.ts >"$refused/site-host.log" 2>&1; then
  echo "web: the website's build accepted a missing IDLEO_HOST_URL"; exit 1
fi
grep -q "needs IDLEO_HOST_URL" "$refused/site-host.log"
# And the admin site's build without its own address.
without_admin=()
for setting in "${production_env[@]}"; do [[ $setting == IDLEO_ADMIN_URL=* ]] || without_admin+=("$setting"); done
if env "${without_admin[@]}" IDLEO_WEB_SITE=admin pnpm exec vite build --outDir "$refused/dist-admin" >"$refused/admin.log" 2>&1; then
  echo "web: a production build of the admin site accepted a missing IDLEO_ADMIN_URL"; exit 1
fi
grep -q "needs IDLEO_ADMIN_URL" "$refused/admin.log"
rm -rf "$refused"
