#!/usr/bin/env bash
# Builds the job image (agent/images/pytorch/, ADR 0035), pushes it and prints the digest the catalogs pin.
#
#   scripts/images/publish.sh --dev
#       The development variant (linux/arm64, CPU PyTorch: what a Mac's development distribution runs) to the local registry
#       (control-plane/compose.yaml's `registry`, started if it isn't), under the name the development catalog uses
#       (registry.idleo.test:5000/idleo/pytorch). Writes private/local/development-real.yaml: templates/development-real.yaml
#       with the placeholder digest replaced by the one just pushed. ./start.sh --real-runner runs the stack with it.
#
#   scripts/images/publish.sh --dev --cuda
#       The image that ships (linux/amd64, CUDA 12.8), built here and pushed to the same local registry, for a Windows laptop to
#       pull (./start.sh --lan --real-runner), with the production arguments: the real fine-tune, on its GPU. Slow the first time.
#
#   scripts/images/publish.sh --release --registry ghcr.io/OWNER/idleo-pytorch [--tag 0.1.0]
#       The image that ships (linux/amd64, CUDA 12.8) to a public registry, with the credentials you logged in with
#       (`docker login ghcr.io`); this script never asks for or stores a token. Then it updates templates/catalog.yaml to that
#       digest: the BEGIN/END real-templates block of templates/development-real.yaml, with each `# dev: VALUE in production`
#       line given that value and the image pinned, replaces the block there (or is added, the first time). Review the diff and commit it with the push.
#       Until you have published, the production catalog keeps only the smoke test; a digest that isn't in a registry is
#       never committed.
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
mode=""
cuda=false
registry=""
tag="$(date -u +%Y%m%d-%H%M%S)"
while (($# > 0)); do
  case "$1" in
    --dev) mode=dev ;;
    --release) mode=release ;;
    --cuda) cuda=true ;;
    --registry) registry="$2"; shift ;;
    --tag) tag="$2"; shift ;;
    *) echo "publish: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
[[ -n "$mode" ]] || { sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2; exit 2; }
command -v docker >/dev/null || { echo "publish: Docker isn't installed" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "publish: Docker isn't running" >&2; exit 1; }
here=agent/images/pytorch
placeholder="sha256:$(printf '0%.0s' $(seq 1 64))"

if [[ "$mode" == dev && "$cuda" == true ]]; then
  [[ -n "$(docker ps -q --filter publish=5000)" ]] || { echo "publish: no registry is running on port 5000 (./start.sh, or compose's registry)" >&2; exit 1; }
  echo "publish: building the image that ships (linux/amd64, CUDA 12.8) for the local registry"
  docker buildx build --platform linux/amd64 --provenance=false --load -t localhost:5000/idleo/pytorch:dev-cuda "$here" >/dev/null
  digest="$(docker push localhost:5000/idleo/pytorch:dev-cuda 2>&1 | sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' | tail -1)"
  [[ -n "$digest" ]] || { echo "publish: the push gave no digest" >&2; exit 1; }
  mkdir -p private/local
  # The development catalog with this image, and the arguments the image ships with: `args: [...] # dev: [...] in production`.
  python3 - "$digest" "$placeholder" <<'PY'
import re, sys
digest, placeholder = sys.argv[1:]
text = open("templates/development-real.yaml").read().replace(placeholder, digest)
text = re.sub(r"^(\s*args:) .* # dev: (.*) in production$", lambda m: f"{m.group(1)} {m.group(2)}", text, flags=re.M)
open("private/local/development-real.yaml", "w").write(text)
PY
  echo "publish: registry.idleo.test:5000/idleo/pytorch@$digest"
  echo "publish: private/local/development-real.yaml written, with the production arguments"
  exit 0
fi
if [[ "$mode" == dev ]]; then
  registry_digest="registry:2@sha256:a3d8aaa63ed8681a604f1dea0aa03f100d5895b6a58ace528858a7b332415373"
  # 127.0.0.1, not localhost: macOS's AirPlay Receiver holds *:5000, and localhost may mean it.
  if ! curl -fsS http://127.0.0.1:5000/v2/ >/dev/null 2>&1; then
    docker rm -f idleo-dev-registry >/dev/null 2>&1 || true
    docker run -d --name idleo-dev-registry -p 127.0.0.1:5000:5000 "$registry_digest" >/dev/null
    for _ in $(seq 1 30); do curl -fsS http://127.0.0.1:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
  fi
  echo "publish: building the development image (linux/arm64, CPU PyTorch)"
  docker buildx build --provenance=false --load \
    --build-arg BASE=ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac \
    --build-arg LOCK=requirements.dev.lock --build-arg TORCH_INDEX=https://download.pytorch.org/whl/cpu \
    -t localhost:5000/idleo/pytorch:dev "$here" >/dev/null
  digest="$(docker push localhost:5000/idleo/pytorch:dev 2>&1 | sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' | tail -1)"
  [[ -n "$digest" ]] || { echo "publish: the push gave no digest" >&2; exit 1; }
  mkdir -p private/local
  sed "s/registry.idleo.test:5000\/idleo\/pytorch@$placeholder/registry.idleo.test:5000\/idleo\/pytorch@$digest/" \
    templates/development-real.yaml >private/local/development-real.yaml
  echo "publish: registry.idleo.test:5000/idleo/pytorch@$digest"
  echo "publish: private/local/development-real.yaml written (SESSIONS_TEMPLATES_CATALOG=file:private/local/development-real.yaml)"
  exit 0
fi

[[ -n "$registry" ]] || { echo "publish: --release needs --registry ghcr.io/OWNER/NAME" >&2; exit 2; }
[[ "$registry" =~ ^[a-z0-9._-]+(:[0-9]+)?(/[a-z0-9._-]+)+$ ]] || { echo "publish: $registry isn't a registry path" >&2; exit 2; }
echo "publish: building the image that ships (linux/amd64, CUDA 12.8) and pushing $registry:$tag"
meta="$(mktemp)"
trap 'rm -f "$meta"' EXIT
docker buildx build --platform linux/amd64 --provenance=false --push --metadata-file "$meta" -t "$registry:$tag" "$here"
digest="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["containerimage.digest"])' "$meta")"
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "publish: no digest came back" >&2; exit 1; }
echo "publish: $registry@$digest"

python3 - "$registry@$digest" <<'PY'
import re, sys
image = sys.argv[1]
dev = open("templates/development-real.yaml").read()
block = dev[dev.index("  # BEGIN real-templates\n") : dev.index("  # END real-templates\n") + len("  # END real-templates\n")]
def production(line):
    # `args: [..., "--test-cpu"] # dev: ["/opt/idleo/finetune.py"] in production` is `args: ["/opt/idleo/finetune.py"]` there.
    found = re.match(r"^(\s*[a-z_]+:) .* # dev: (.*) in production$", line.rstrip("\n"))
    return f"{found.group(1)} {found.group(2)}\n" if found else line

block = "".join(production(line) for line in block.splitlines(keepends=True))
block = re.sub(r"registry\.idleo\.test:5000/idleo/pytorch@sha256:[0-9a-f]{64}", image, block)
catalog = open("templates/catalog.yaml").read()
if "  # BEGIN real-templates\n" in catalog:
    start = catalog.index("  # BEGIN real-templates\n")
    end = catalog.index("  # END real-templates\n") + len("  # END real-templates\n")
    catalog = catalog[:start] + block + catalog[end:]
else:
    marker = "templates:\n"
    at = catalog.index(marker) + len(marker)
    catalog = catalog[:at] + block + catalog[at:]
open("templates/catalog.yaml", "w").write(catalog)
PY
echo "publish: templates/catalog.yaml updated; review the diff, run the control plane's tests, and commit it with the push"
