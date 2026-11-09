#!/usr/bin/env python3
"""Every admin write the permission matrix says an Auditor may not make, as the console would send it, against the real
stack (ADR 0057): each must answer 403 and change nothing. Paths' variables get a made-up id, which doesn't matter: the
permission is checked first.

    scripts/e2e/admin-writes-refused.py API_URL BEARER_TOKEN ADMIN_SESSION MATRIX_CSV
"""
from __future__ import annotations

import csv
import re
import sys
import urllib.error
import urllib.request
import uuid


def status(api: str, token: str, session: str, method: str, path: str) -> int:
    request = urllib.request.Request(
        api + path,
        method=method,
        data=b"{}" if method != "DELETE" else None,
        headers={
            "Authorization": f"Bearer {token}",
            "Admin-Session": session,
            "Content-Type": "application/json",
            "Idempotency-Key": f'"{uuid.uuid4()}"',
            "If-Match": "1",
        },
    )
    try:
        with urllib.request.urlopen(request) as answer:
            return answer.status
    except urllib.error.HTTPError as answer:
        return answer.code


def main() -> int:
    api, token, session, matrix = sys.argv[1:5]
    wrongly_allowed = []
    refused = 0
    with open(matrix, encoding="utf-8") as rows:
        for row in csv.DictReader(rows):
            method, template = row["endpoint"].split(" ", 1)
            if method == "GET" or row["auditor"] == "allow":
                continue
            path = re.sub(r"\{[^}]+\}", str(uuid.uuid4()), template)
            answer = status(api, token, session, method, path)
            if answer == 403:
                refused += 1
            else:
                wrongly_allowed.append(f"{row['endpoint']} answered {answer}")
    print(f"the Auditor was refused {refused} writes")
    if wrongly_allowed:
        print("\n".join(wrongly_allowed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
