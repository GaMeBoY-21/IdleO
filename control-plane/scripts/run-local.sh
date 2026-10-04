#!/usr/bin/env bash
# Run the control plane on this machine with the local profile, and only that.
# Loads control-plane/.env if present, and refuses to start if anything asks
# for the prod profile, so production settings can never leak into a local run.
set -euo pipefail

cd "$(dirname "$0")/.."

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

exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=local "$@"
