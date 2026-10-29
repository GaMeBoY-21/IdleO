#!/usr/bin/env bash
# Checks sbom.py against a small invented `cargo metadata` and package list: only normal dependencies of the named
# programs are listed (not build or dev ones, not other members), licences and purls are right, the same input gives the
# same file, a program that isn't a member and an empty result are refused.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail() { echo "sbom-test: $1" >&2; exit 1; }
cat >"$work/metadata.json" <<'JSON'
{
  "workspace_members": ["app 1.0.0", "other 1.0.0"],
  "packages": [
    {"id": "app 1.0.0", "name": "app", "version": "1.0.0", "license": null},
    {"id": "other 1.0.0", "name": "other", "version": "1.0.0", "license": null},
    {"id": "lib 2.0.0", "name": "lib", "version": "2.0.0", "license": "MIT OR Apache-2.0"},
    {"id": "deep 0.3.1", "name": "deep", "version": "0.3.1", "license": "MIT"},
    {"id": "builder 1.0.0", "name": "builder", "version": "1.0.0", "license": "MIT"},
    {"id": "tester 1.0.0", "name": "tester", "version": "1.0.0", "license": "MIT"},
    {"id": "unrelated 1.0.0", "name": "unrelated", "version": "1.0.0", "license": "MIT"}
  ],
  "resolve": {"nodes": [
    {"id": "app 1.0.0", "deps": [
      {"pkg": "lib 2.0.0", "dep_kinds": [{"kind": null}]},
      {"pkg": "builder 1.0.0", "dep_kinds": [{"kind": "build"}]},
      {"pkg": "tester 1.0.0", "dep_kinds": [{"kind": "dev"}]}]},
    {"id": "lib 2.0.0", "deps": [{"pkg": "deep 0.3.1", "dep_kinds": [{"kind": null}]}]},
    {"id": "deep 0.3.1", "deps": []},
    {"id": "builder 1.0.0", "deps": []},
    {"id": "tester 1.0.0", "deps": []},
    {"id": "other 1.0.0", "deps": [{"pkg": "unrelated 1.0.0", "dep_kinds": [{"kind": null}]}]},
    {"id": "unrelated 1.0.0", "deps": []}
  ]}
}
JSON
printf 'adduser 3.137ubuntu1\nbash 5.2.21-2ubuntu4\n\n' >"$work/packages.txt"
sbom() { python3 "$here/sbom.py" --timestamp 2026-01-01T00:00:00Z "$@" 2>/dev/null; }

sbom --name idleo-app --version 9.9.9 --out "$work/app.json" --metadata "$work/metadata.json" --package app
python3 - "$work/app.json" <<'PY' || fail "the cargo bill of materials is wrong"
import json, sys
bom = json.load(open(sys.argv[1]))
assert bom["bomFormat"] == "CycloneDX" and bom["specVersion"] == "1.5", bom
assert bom["serialNumber"].startswith("urn:uuid:"), bom
assert bom["metadata"]["component"]["name"] == "idleo-app" and bom["metadata"]["component"]["version"] == "9.9.9"
names = [c["name"] for c in bom["components"]]
assert names == ["app", "deep", "lib"], names
lib = next(c for c in bom["components"] if c["name"] == "lib")
assert lib["purl"] == "pkg:cargo/lib@2.0.0" and lib["licenses"] == [{"expression": "MIT OR Apache-2.0"}], lib
assert next(c for c in bom["components"] if c["name"] == "app")["type"] == "application"
PY

sbom --name idleo-app --version 9.9.9 --out "$work/app2.json" --metadata "$work/metadata.json" --package app
cmp -s "$work/app.json" "$work/app2.json" || fail "the same input gave two different files"

sbom --name idleo-distro --version 0.1.0 --out "$work/distro.json" --packages "$work/packages.txt" --distro ubuntu-24.04 \
  --metadata "$work/metadata.json" --package other
python3 - "$work/distro.json" <<'PY' || fail "the package list's bill of materials is wrong"
import json, sys
bom = json.load(open(sys.argv[1]))
purls = sorted(c["purl"] for c in bom["components"] if "purl" in c)
assert purls == [
    "pkg:cargo/unrelated@1.0.0",
    "pkg:deb/ubuntu/adduser@3.137ubuntu1?distro=ubuntu-24.04",
    "pkg:deb/ubuntu/bash@5.2.21-2ubuntu4?distro=ubuntu-24.04",
], purls
assert "other" in [c["name"] for c in bom["components"]]
PY

if sbom --name x --version 1.0.0 --out "$work/none.json" --metadata "$work/metadata.json" --package nothere; then
  fail "a program that isn't a workspace member was accepted"
fi
: >"$work/empty.txt"
if sbom --name x --version 1.0.0 --out "$work/none.json" --packages "$work/empty.txt" --distro ubuntu-24.04; then
  fail "an empty bill of materials was written"
fi
[[ ! -e "$work/none.json" ]] || fail "a refused run left a file behind"
echo "sbom-test: ok"
