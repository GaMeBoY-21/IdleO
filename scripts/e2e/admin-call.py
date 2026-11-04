#!/usr/bin/env python3
"""One admin action for the end-to-end scripts, as the console does it (ADR 0051): an Idempotency-Key on the POST, the record's
version in If-Match when it's given, a factor when the server asks for a recent one, and the same request again; and when the
action goes to another admin, that admin's approval. It prints the last answer's body, and exits 1 if the action was refused.

    admin-call.py [--if-match <version>] <path> [<json body>]

From the environment: E2E_API, and E2E_ADMIN_TOKEN, E2E_ADMIN_SESSION, E2E_ADMIN_SECRET and E2E_ADMIN_STEPS for the admin;
E2E_APPROVER_TOKEN, E2E_APPROVER_SESSION, E2E_APPROVER_SECRET and E2E_APPROVER_STEPS for the second one. A secret is the
authenticator's, as the server showed it. Steps names a file keeping the newest time step used, since a code works once for its
step.
"""
import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid


def code(secret: str, step: int) -> str:
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[19] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def fresh_code(secret: str, used_file: str) -> str:
    """A code for a step not used yet: one ahead of now is accepted once, or else it waits for the next step."""
    used = int(open(used_file, encoding="utf-8").read()) if os.path.exists(used_file) else 0
    step = int(time.time() // 30) + 1
    while step <= used:
        time.sleep(1)
        step = int(time.time() // 30) + 1
    with open(used_file, "w", encoding="utf-8") as kept:
        kept.write(str(step))
    return code(secret, step)


def send(api: str, who: dict, path: str, body: bytes, key: str | None = None, if_match: str | None = None):
    headers = {
        "Authorization": f"Bearer {who['token']}",
        "Admin-Session": who["session"],
        "Content-Type": "application/json",
    }
    if key:
        headers["Idempotency-Key"] = key
    if if_match:
        headers["If-Match"] = if_match
    request = urllib.request.Request(api + path, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=120) as answer:
            return answer.status, answer.read().decode()
    except urllib.error.HTTPError as refused:
        return refused.code, refused.read().decode()


def act(api: str, who: dict, path: str, body: bytes, if_match: str | None = None):
    """The request, with a factor if it's asked for: the same key both times."""
    key = str(uuid.uuid4())
    status, text = send(api, who, path, body, key, if_match)
    if status == 403 and json.loads(text).get("type") == "/problems/step-up-required":
        given = json.dumps({"totp": fresh_code(who["secret"], who["steps"])}).encode()
        stepped, answer = send(api, who, "/admin/v1/session/step-up", given)
        if stepped != 200:
            print(answer)
            sys.exit(1)
        status, text = send(api, who, path, body, key, if_match)
    return status, text


def main() -> None:
    args = sys.argv[1:]
    if_match = None
    if args[0] == "--if-match":
        if_match, args = args[1], args[2:]
    path = args[0]
    body = (args[1] if len(args) > 1 else "{}").encode()
    api = os.environ["E2E_API"]
    admin = {
        "token": os.environ["E2E_ADMIN_TOKEN"],
        "session": os.environ["E2E_ADMIN_SESSION"],
        "secret": os.environ["E2E_ADMIN_SECRET"],
        "steps": os.environ["E2E_ADMIN_STEPS"],
    }
    status, text = act(api, admin, path, body, if_match)
    if status == 202:
        approval = json.loads(text)
        approver = {
            "token": os.environ["E2E_APPROVER_TOKEN"],
            "session": os.environ["E2E_APPROVER_SESSION"],
            "secret": os.environ["E2E_APPROVER_SECRET"],
            "steps": os.environ["E2E_APPROVER_STEPS"],
        }
        reason = json.dumps({"reason": "checked by a second admin"}).encode()
        status, text = act(api, approver, f"/admin/v1/approvals/{approval['id']}/approve", reason, str(approval["version"]))
    print(text)
    sys.exit(0 if 200 <= status < 300 else 1)


if __name__ == "__main__":
    main()
