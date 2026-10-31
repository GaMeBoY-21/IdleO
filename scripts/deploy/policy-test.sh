#!/usr/bin/env bash
# kyverno test on a cluster's policies as Flux builds them (ADR 0048): what they admit and refuse, signatures included,
# checked against the real registries and the public transparency log (deploy/policies/tests). scripts/checks/deploy.sh
# runs it on staging's and production's; scripts/k8s/security-test.sh on staging's again, from the Mac's cluster.
#
#   scripts/deploy/policy-test.sh <the built policies, a file of YAML documents>
set -euo pipefail
[[ $# == 1 && -s "$1" ]] || { echo "usage: $0 <built policies.yaml>" >&2; exit 2; }
cd "$(dirname "$0")/../.."
# Kyverno's CLI, of the Kyverno the clusters run (deploy/infrastructure/base/kyverno.yaml).
kyverno_image=ghcr.io/kyverno/kyverno-cli:v1.19.2@sha256:322ec5044d600930cb3af0e56dc33154a2d1d1fba58f4cadad3f47be7e78b11c
tests="$(mktemp -d)"
trap 'rm -rf "$tests"' EXIT
cp deploy/policies/tests/kyverno-test.yaml deploy/policies/tests/resources.yaml "$tests/"
# The allowed list is the admission policies' parameter; everything else is a policy.
python3 - "$1" "$tests" <<'PY'
import sys, yaml
documents = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
yaml.safe_dump_all([d for d in documents if d["kind"] == "ConfigMap"], open(f"{sys.argv[2]}/params.yaml", "w"))
yaml.safe_dump_all([d for d in documents if d["kind"] != "ConfigMap"], open(f"{sys.argv[2]}/policies.yaml", "w"))
PY
chmod -R a+rwX "$tests"
docker run --rm -v "$tests:/tests" -w /tests "$kyverno_image" test . --registry --remove-color --fail-only
