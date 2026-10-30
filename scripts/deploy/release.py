#!/usr/bin/env python3
"""An environment's release (ADR 0046): deploy/clusters/<env>/idleo/release.yaml, which pins the chart and every image
by digest. A deploy is a pull request that changes this file and nothing else.

  scripts/deploy/release.py resolve <commit>
      The digests GHCR holds for a build of main: the chart (charts/idleo:<version>-<commit>) and the four images
      (idleo-<name>:<commit>), as JSON. Anonymous: the packages are public.
  scripts/deploy/release.py write <env> <commit>
      Resolves that build and writes it as <env>'s release.
  scripts/deploy/release.py copy <from> <to>
      Writes <from>'s release as <to>'s (promoting staging to production).
  scripts/deploy/release.py commit <env>
      The commit <env> runs.
"""
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
REGISTRY = "ghcr.io"
OWNER = "gameboy-21"
IMAGES = ("control-plane", "web", "scanner", "postgres")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
ACCEPT = ", ".join((
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.docker.distribution.manifest.v2+json",
))


def release_file(environment):
    if environment not in ("staging", "production"):
        sys.exit(f"release: no such environment: {environment} (staging or production)")
    return ROOT / "deploy" / "clusters" / environment / "idleo" / "release.yaml"


def digest(repository, tag):
    """The manifest digest of repository:tag, as the registry reports it to an anonymous reader."""
    try:
        token_url = f"https://{REGISTRY}/token?scope=repository:{repository}:pull"
        with urllib.request.urlopen(token_url, timeout=30) as reply:
            token = json.load(reply)["token"]
        request = urllib.request.Request(
            f"https://{REGISTRY}/v2/{repository}/manifests/{tag}", method="HEAD",
            headers={"Authorization": f"Bearer {token}", "Accept": ACCEPT})
        with urllib.request.urlopen(request, timeout=30) as reply:
            found = reply.headers["Docker-Content-Digest"]
    except urllib.error.HTTPError as error:
        sys.exit(f"release: {REGISTRY}/{repository}:{tag} can't be read anonymously ({error.code}): not published, or private")
    if not DIGEST.match(found or ""):
        sys.exit(f"release: {REGISTRY}/{repository}:{tag} gave no digest")
    return found


def chart_version():
    chart = (ROOT / "deploy" / "helm" / "idleo" / "Chart.yaml").read_text()
    return re.search(r"^version: (\S+)$", chart, re.M).group(1)


def resolve(commit):
    if not COMMIT.match(commit):
        sys.exit(f"release: {commit} isn't a full commit hash")
    version = f"{chart_version()}-{commit}"
    return {
        "commit": commit,
        "chart": {"version": version, "digest": digest(f"{OWNER}/charts/idleo", version)},
        "images": {name: digest(f"{OWNER}/idleo-{name}", commit) for name in IMAGES},
    }


def write(environment, release):
    images = "\n".join(f"        {name}: {release['images'][name]}" for name in IMAGES)
    release_file(environment).write_text(f"""\
# {environment.capitalize()}'s release (ADR 0046): the build of main it runs, the chart and every image pinned by digest.
# Written by scripts/deploy/release.py; a deploy is a pull request that changes this file and nothing else.
apiVersion: source.toolkit.fluxcd.io/v1
kind: OCIRepository
metadata:
  name: idleo-chart
  namespace: idleo
spec:
  ref:
    # {REGISTRY}/{OWNER}/charts/idleo:{release['chart']['version']}
    digest: {release['chart']['digest']}
---
apiVersion: helm.toolkit.fluxcd.io/v2
kind: HelmRelease
metadata:
  name: idleo
  namespace: idleo
spec:
  values:
    images:
      tag: "{release['commit']}"
      digests:
{images}
""")


def read(environment):
    text = release_file(environment).read_text()
    found = lambda pattern: re.search(pattern, text, re.M).group(1)
    return {
        "commit": found(r'^      tag: "([0-9a-f]{40})"$'),
        "chart": {"version": found(r"charts/idleo:(\S+)$"), "digest": found(r"^    digest: (sha256:[0-9a-f]{64})$")},
        "images": {name: found(rf"^        {re.escape(name)}: (sha256:[0-9a-f]{{64}})$") for name in IMAGES},
    }


def main(arguments):
    command, rest = (arguments[0], arguments[1:]) if arguments else ("", [])
    if command == "resolve" and len(rest) == 1:
        print(json.dumps(resolve(rest[0]), indent=2))
    elif command == "write" and len(rest) == 2:
        write(rest[0], resolve(rest[1]))
    elif command == "copy" and len(rest) == 2:
        write(rest[1], read(rest[0]))
    elif command == "commit" and len(rest) == 1:
        print(read(rest[0])["commit"])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
