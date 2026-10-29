#!/usr/bin/env python3
"""Writes a CycloneDX 1.5 software bill of materials (JSON) for a release's files, from either or both of:

  --package NAME ... --workspace DIR --target TRIPLE
      the Rust crates the named programs are built from (normal dependencies, for that target), from
      `cargo metadata --locked`, so it is the Cargo.lock the build used;
  --packages FILE --distro ubuntu-24.04
      the Ubuntu packages in the sealed Linux image, from the "name version" list build-distro.sh writes.

  sbom.py --name idleo-app --version 0.2.0 --out idleo-app-0.2.0.cdx.json \
          --workspace agent --target x86_64-pc-windows-msvc --package agent --package tray
  sbom.py --name idleo-distro --version 0.1.0 --out idleo-distro-0.1.0.cdx.json \
          --packages dist/distro/idleo-distro-0.1.0.packages.txt --distro ubuntu-24.04 \
          --workspace agent --target x86_64-unknown-linux-gnu --package runner

The output is the same for the same input: the serial number is derived from the content, and the time is
SOURCE_DATE_EPOCH when set (--timestamp otherwise defaults to now). Standard library only.
"""
import argparse
import datetime
import json
import os
import subprocess
import sys
import urllib.parse
import uuid


def purl(kind, name, version, qualifiers=None, namespace=None):
    out = f"pkg:{kind}/"
    if namespace:
        out += urllib.parse.quote(namespace) + "/"
    out += urllib.parse.quote(name) + "@" + urllib.parse.quote(version, safe="")
    if qualifiers:
        out += "?" + "&".join(f"{k}={urllib.parse.quote(v)}" for k, v in sorted(qualifiers.items()))
    return out


def bom(name, version, components, timestamp):
    components = sorted(components, key=lambda c: (c["name"], c["version"]))
    body = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "component": {"type": "application", "bom-ref": f"{name}@{version}", "name": name, "version": version},
        },
        "components": components,
    }
    body["serialNumber"] = "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps(body, sort_keys=True)))
    return body


def cargo_components(metadata, programs):
    """The packages reachable from `programs` through normal (not build or dev) dependencies."""
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    roots = [p["id"] for p in metadata["packages"] if p["name"] in programs and p["id"] in metadata["workspace_members"]]
    missing = set(programs) - {packages[r]["name"] for r in roots}
    if missing:
        raise SystemExit(f"sbom: not workspace members: {', '.join(sorted(missing))}")
    seen, stack = set(), list(roots)
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        for dep in nodes[current]["deps"]:
            if any(kind["kind"] is None for kind in dep["dep_kinds"]):
                stack.append(dep["pkg"])
    members = set(metadata["workspace_members"])
    components = []
    for pid in seen:
        p = packages[pid]
        component = {
            "type": "application" if pid in roots else "library",
            "bom-ref": purl("cargo", p["name"], p["version"]),
            "name": p["name"],
            "version": p["version"],
        }
        if pid not in members:
            component["purl"] = component["bom-ref"]
        if p.get("license"):
            component["licenses"] = [{"expression": p["license"]}]
        components.append(component)
    return components


def deb_components(path, distro):
    components = []
    for line in open(path, encoding="utf-8").read().splitlines():
        if not line.strip():
            continue
        name, version = line.split(" ", 1)
        ref = purl("deb", name, version.strip(), {"distro": distro}, namespace="ubuntu")
        components.append({"type": "library", "bom-ref": ref, "name": name, "version": version.strip(), "purl": ref})
    return components


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--timestamp")
    parser.add_argument("--workspace", help="the folder holding the Cargo.toml")
    parser.add_argument("--target", help="the target triple the programs are built for")
    parser.add_argument("--package", action="append", default=[], help="a program built from the workspace; repeat for each")
    parser.add_argument("--metadata", help="read this `cargo metadata` JSON instead of running it (tests)")
    parser.add_argument("--packages", help="the file of `name version` lines")
    parser.add_argument("--distro", help="the distribution, e.g. ubuntu-24.04")
    args = parser.parse_args()

    if args.timestamp:
        timestamp = args.timestamp
    elif os.environ.get("SOURCE_DATE_EPOCH"):
        timestamp = datetime.datetime.fromtimestamp(int(os.environ["SOURCE_DATE_EPOCH"]), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    else:
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    components = []
    if args.package:
        if not args.metadata and not (args.workspace and args.target):
            parser.error("--package needs --workspace and --target (or --metadata)")
        if args.metadata:
            metadata = json.load(open(args.metadata, encoding="utf-8"))
        else:
            metadata = json.loads(
                subprocess.run(
                    ["cargo", "metadata", "--format-version", "1", "--locked", "--filter-platform", args.target],
                    cwd=args.workspace, check=True, capture_output=True, text=True,
                ).stdout
            )
        components += cargo_components(metadata, set(args.package))
    if args.packages:
        if not args.distro:
            parser.error("--packages needs --distro")
        components += deb_components(args.packages, args.distro)
    if not components:
        raise SystemExit("sbom: no components found; refusing to write an empty bill of materials")
    with open(args.out, "w", encoding="utf-8") as out:
        json.dump(bom(args.name, args.version, components, timestamp), out, indent=2)
        out.write("\n")
    print(f"sbom: {args.out}: {len(components)} components", file=sys.stderr)


if __name__ == "__main__":
    main()
