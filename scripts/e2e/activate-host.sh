#!/usr/bin/env bash
# Activates a host as an operator would (docs/runbooks/hosts.md), against the stack scripts/e2e/agent-online.sh started:
# the web journeys that add a machine call this for the step an operator does by hand until onboarding's checks exist.
#   scripts/e2e/activate-host.sh <host id>
set -euo pipefail
[[ $# == 1 ]] || { echo "usage: $0 <host id>" >&2; exit 2; }
cd "$(dirname "$0")/../../control-plane"
LOCAL_SIGN_IN_PORT="${LOCAL_SIGN_IN_PORT:-8099}" COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-idleo-e2e}" \
  OPERATOR="${OPERATOR:-e2e-script}" ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local,ops \
  -Dspring-boot.run.arguments="activate-host --host $1 --reason 'End-to-end journey'"
