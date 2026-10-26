#!/usr/bin/env python3
"""Builds a test container image byte by byte and pushes it to a registry's HTTP API (ADR 0042), for the end-to-end tests of
renters' own images. No Docker daemon is involved, so an image can hold what no honest build would: an executable deep in a
lower layer, a layer that is tiny to push and huge once unpacked, a path with `..`. Never run against anything but the
development stack's registry; nothing here is real software (the "executables" are the ELF magic and a marker).

    scripts/e2e/make-image.py REGISTRY REPOSITORY TAG KIND [--arch amd64|arm64] [--user USER:TOKEN]

REGISTRY is host:port (plain HTTP), REPOSITORY like test/clean. KIND is one of:

    clean        a small image with a shell, nothing wrong
    listed       an ELF file whose bytes are `--marker`'s, deep in the lowest layer and deleted by the layer above
    secret       a file /app/.env holding a made-up AWS key, and USER root
    bomb         one layer of zeroes: a few KiB to push, `--unpacked-gib` GiB unpacked
    traversal    a layer with a path that climbs out of the image
    cuda13       clean, but its config says it needs CUDA 13.0
    heavy        clean, with one layer of `--wire-mb` MB of random bytes: big on the wire, for a download to run long

It prints the image's manifest digest (`sha256:…`) on the last line. With `--user`, the registry is spoken to with basic auth.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import json
import sys
import tarfile
import urllib.error
import urllib.request

ELF = b"\x7fELF\x02\x01\x01\x00"


def sha(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def layer(files: dict[str, bytes | None], *, links: dict[str, str] | None = None, zeroes: int = 0) -> tuple[bytes, str]:
    """A gzip layer holding `files` (None is a whiteout of that path), and its uncompressed digest (the diff id)."""
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as tar:
        for path, body in files.items():
            if body is None:
                directory, _, name = path.rpartition("/")
                info = tarfile.TarInfo(f"{directory + '/' if directory else ''}.wh.{name}")
                info.size = 0
                tar.addfile(info)
                continue
            info = tarfile.TarInfo(path)
            info.size = len(body)
            info.mode = 0o755
            tar.addfile(info, io.BytesIO(body))
        for path, target in (links or {}).items():
            info = tarfile.TarInfo(path)
            info.type = tarfile.SYMTYPE
            info.linkname = target
            tar.addfile(info)
        if zeroes:
            info = tarfile.TarInfo("data/zeroes.bin")
            info.size = zeroes
            tar.addfile(info, ZeroFile(zeroes))
    data = raw.getvalue()
    return gzip.compress(data, compresslevel=9, mtime=0), sha(data)


class ZeroFile(io.RawIOBase):
    """`size` zero bytes, never held in memory."""

    def __init__(self, size: int) -> None:
        self.left = size

    def readable(self) -> bool:
        return True

    def readinto(self, buffer) -> int:
        count = min(len(buffer), self.left)
        buffer[:count] = bytes(count)
        self.left -= count
        return count


def build(kind: str, arch: str, marker: str, unpacked_gib: float, wire_mb: int = 20) -> tuple[list[tuple[bytes, str]], dict]:
    sh = {"bin/sh": ELF + b"dash"}
    config: dict = {"Env": ["PATH=/usr/bin"], "Cmd": ["python3", "main.py"], "WorkingDir": "/app", "User": "10001"}
    layers = [layer({**sh, "app/main.py": b"print('hello')\n"})]
    if kind == "listed":
        layers = [layer({**sh, "opt/tools/deep/inside/miner": ELF + marker.encode()}), layer({"opt/tools/deep/inside/miner": None})]
    elif kind == "secret":
        # Made up, and not AWS's documented example key, which scanners allow-list: this must look real to Trivy.
        env = b"AWS_ACCESS_KEY_ID=AKIAQWERTYUIOPASDFGH\nAWS_SECRET_ACCESS_KEY=Zx9pLm3Qr7Tv1Wy5Bn8Kd2Fh6Js4Gc0Ae3Ri7Uo1\n"
        layers = [layer({**sh, "app/.env": env})]
        config["User"] = "root"
    elif kind == "bomb":
        layers = [layer({**sh}), layer({}, zeroes=int(unpacked_gib * (1 << 30)))]
    elif kind == "traversal":
        layers = [layer({**sh}), layer({"app/../../../etc/cron.d/x": b"* * * * * root true\n"})]
    elif kind == "heavy":
        import os

        layers.append(layer({"opt/weights.bin": os.urandom(wire_mb * 1_000_000)}))
    elif kind == "cuda13":
        config["Env"] += ["CUDA_VERSION=13.0.1", "NVIDIA_REQUIRE_CUDA=cuda>=13.0"]
    elif kind != "clean":
        raise SystemExit(f"unknown kind {kind}")
    return layers, config


def request(method: str, url: str, *, data: bytes | None = None, headers: dict | None = None, auth: str | None = None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if auth:
        req.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
    try:
        return urllib.request.urlopen(req, timeout=120)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {url} answered {e.code}: {e.read().decode()[:200]}")


def push_blob(base: str, repo: str, data: bytes, auth: str | None) -> str:
    digest = sha(data)
    started = request("POST", f"{base}/v2/{repo}/blobs/uploads/", auth=auth)
    location = started.headers["Location"]
    location = location if location.startswith("http") else base + location
    request("PUT", f"{location}{'&' if '?' in location else '?'}digest={digest}", data=data,
            headers={"Content-Type": "application/octet-stream"}, auth=auth)
    return digest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("registry")
    parser.add_argument("repository")
    parser.add_argument("tag")
    parser.add_argument("kind")
    parser.add_argument("--arch", default="amd64")
    parser.add_argument("--user")
    parser.add_argument("--marker", default="a build on the test blocklist")
    parser.add_argument("--unpacked-gib", type=float, default=2.0)
    parser.add_argument("--wire-mb", type=int, default=20)
    args = parser.parse_args()
    base = f"http://{args.registry}"
    layers, config = build(args.kind, args.arch, args.marker, args.unpacked_gib, args.wire_mb)
    config_json = json.dumps({
        "architecture": args.arch,
        "os": "linux",
        "config": config,
        "rootfs": {"type": "layers", "diff_ids": [diff for _, diff in layers]},
    }).encode()
    descriptors = []
    for blob, _ in layers:
        push_blob(base, args.repository, blob, args.user)
        descriptors.append({"mediaType": "application/vnd.oci.image.layer.v1.tar+gzip", "digest": sha(blob), "size": len(blob)})
    push_blob(base, args.repository, config_json, args.user)
    manifest = json.dumps({
        "schemaVersion": 2,
        "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": sha(config_json), "size": len(config_json)},
        "layers": descriptors,
    }).encode()
    request("PUT", f"{base}/v2/{args.repository}/manifests/{args.tag}", data=manifest,
            headers={"Content-Type": "application/vnd.oci.image.manifest.v1+json"}, auth=args.user)
    print(sha(manifest))


if __name__ == "__main__":
    sys.exit(main())
