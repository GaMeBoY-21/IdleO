#!/usr/bin/env python3
"""Starts an admin session for the end-to-end scripts (ADR 0027): enrols a second factor for the signed-in admin and prints the
`Admin-Session` token. The admin must already have the role (`grant-admin`); the TOTP code is worked out here from the secret
the server shows (RFC 6238: HMAC-SHA1, 30 s steps, 6 digits), as an authenticator app would.

    scripts/e2e/admin-session.py API_URL BEARER_TOKEN
"""
import base64
import hashlib
import hmac
import json
import struct
import sys
import time
import urllib.error
import urllib.request


def code(secret: str) -> str:
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return f"{(struct.unpack('>I', digest[offset : offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def post(api: str, bearer: str, path: str, body=None):
    request = urllib.request.Request(
        api + path,
        method="POST",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {bearer}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as answer:
            return json.loads(answer.read() or b"{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"POST {path} answered {e.code}: {e.read().decode()[:300]}")


def main() -> None:
    api, bearer = sys.argv[1].rstrip("/"), sys.argv[2]
    secret = post(api, bearer, "/admin/v1/second-factor")["secret"]
    confirmed = post(api, bearer, "/admin/v1/second-factor/confirm", {"totp": code(secret)})
    print(confirmed["token"])


if __name__ == "__main__":
    main()
