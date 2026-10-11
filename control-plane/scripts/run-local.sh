#!/usr/bin/env bash
# Run the control plane on this machine with the local profile, and only that.
# Loads control-plane/.env if present, and refuses to start if anything asks
# for the prod profile, so production settings can never leak into a local run.
#
# By default people sign in through the development sign-in server, whatever .env
# says. With --real-sign-in the control plane trusts your own Identity Platform
# project instead, from IDENTITY_ISSUERS_0_ISSUERURI and IDENTITY_ISSUERS_0_AUDIENCE
# in .env (docs/runbooks/identity-provider-setup.md).
set -euo pipefail

cd "$(dirname "$0")/.."

real_sign_in=0
args=()
for arg in "$@"; do
  if [[ "$arg" == "--real-sign-in" ]]; then
    real_sign_in=1
  else
    args+=("$arg")
  fi
done

if [[ -f .env ]]; then
  set -a
  # shellcheck source=/dev/null
  . ./.env
  set +a
fi

for var in SPRING_PROFILES_ACTIVE SPRING_PROFILES_INCLUDE; do
  value="${!var:-}"
  lower="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  if [[ "$lower" == *prod* ]]; then
    echo "run-local: $var=$value asks for the prod profile. Local runs use only the local profile; unset it (and remove it from .env)." >&2
    exit 1
  fi
done

if ((real_sign_in)); then
  issuer="${IDENTITY_ISSUERS_0_ISSUERURI:-}"
  audience="${IDENTITY_ISSUERS_0_AUDIENCE:-}"
  if [[ -z "$issuer" || -z "$audience" ]]; then
    echo "run-local: --real-sign-in needs IDENTITY_ISSUERS_0_ISSUERURI and IDENTITY_ISSUERS_0_AUDIENCE in control-plane/.env. Check those two lines (identity-provider-setup.md, section 7)." >&2
    exit 1
  fi
  if [[ "$audience" == "demo-project" || "$audience" == "idleo-local" || "$issuer" != https://securetoken.google.com/* ]]; then
    echo "run-local: IDENTITY_ISSUERS_0_ISSUERURI must be https://securetoken.google.com/<your-project-id> and IDENTITY_ISSUERS_0_AUDIENCE your project id, not the example or the development sign-in server. Check those two lines in control-plane/.env." >&2
    exit 1
  fi
  if [[ "$issuer" != "https://securetoken.google.com/$audience" ]]; then
    echo "run-local: the issuer ($issuer) and the audience ($audience) must end in the same project id. Check those two lines in control-plane/.env." >&2
    exit 1
  fi
  echo "run-local: real sign-in: trusting project $audience" >&2
else
  # The development sign-in server, even if .env holds a real project's settings.
  unset IDENTITY_ISSUERS_0_ISSUERURI IDENTITY_ISSUERS_0_AUDIENCE
fi

if [[ ! -f ../private/dev-ca/root.pem ]]; then
  echo "run-local: no development CA yet. Run scripts/dev-ca.sh first." >&2
  exit 1
fi

exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=local "${args[@]+"${args[@]}"}"
