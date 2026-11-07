#!/usr/bin/env python3
"""The gate on our images' scans (ADR 0048), on Trivy's JSON report of an image. A finding is a fixable CRITICAL or HIGH
vulnerability (one Trivy knows a fixed version for) or any CRITICAL or HIGH secret; nothing on our images is ever
accepted, so no ignore file is read here.

    scan-gate.py REPORT                          main's build: every finding fails
    scan-gate.py REPORT --baseline MAIN          a pull request: a vulnerability fails only if main's last scan of the
                                                 same image doesn't have it (the same id in the same package); a secret
                                                 always fails
    scan-gate.py REPORT --baseline-missing WHY   a pull request whose baseline couldn't be fetched: every finding fails
    scan-gate.py --age STATE NOW REPORT...       the nightly scan of what main deployed: keeps each finding's first night
                                                 in STATE (JSON) and prints the oldest one's age in seconds (0: none)

--count FILE writes how many findings failed it, for the alerts (scripts/ops/report-suite.py). Exits 1 when any did.
"""
from __future__ import annotations

import argparse
import datetime
import json
import sys


def image_of(report: dict) -> str:
    """The image's repository, without its tag or digest: a finding stays the same finding across builds."""
    name = report.get("ArtifactName", "")
    name = name.split("@", 1)[0]
    last = name.rsplit("/", 1)[-1]
    return name[: len(name) - len(last)] + last.split(":", 1)[0]


def findings(report: dict) -> list[dict]:
    found = []
    for result in report.get("Results") or []:
        for vulnerability in result.get("Vulnerabilities") or []:
            if vulnerability.get("Severity") in ("CRITICAL", "HIGH") and vulnerability.get("FixedVersion"):
                found.append({
                    "kind": "vulnerability",
                    "key": f"{vulnerability['VulnerabilityID']} {vulnerability['PkgName']}",
                    "text": (f"{vulnerability['Severity']} {vulnerability['VulnerabilityID']} in"
                             f" {vulnerability['PkgName']} {vulnerability.get('InstalledVersion', '')}, fixed in"
                             f" {vulnerability['FixedVersion']} ({result.get('Target', '')})"),
                })
        for secret in result.get("Secrets") or []:
            if secret.get("Severity") in ("CRITICAL", "HIGH"):
                found.append({
                    "kind": "secret",
                    "key": f"{secret.get('RuleID')} {result.get('Target', '')}",
                    "text": f"{secret.get('Severity')} secret {secret.get('RuleID')} in {result.get('Target', '')}",
                })
    return found


def load(path: str) -> dict:
    with open(path) as file:
        return json.load(file)


def gate(report: dict, baseline: dict | None, missing: str | None) -> tuple[list[dict], list[dict]]:
    """(failing, inherited): on main or without a baseline every finding fails; on a pull request a vulnerability
    main's last scan of the same image already had is inherited, reported but not failing."""
    found = findings(report)
    if baseline is None:
        return found, []
    if image_of(baseline) != image_of(report):
        raise SystemExit(f"scan-gate: the baseline is {image_of(baseline)}'s scan, not {image_of(report)}'s")
    on_main = {finding["key"] for finding in findings(baseline) if finding["kind"] == "vulnerability"}
    failing = [f for f in found if f["kind"] == "secret" or f["key"] not in on_main]
    inherited = [f for f in found if f not in failing]
    return failing, inherited


def age(state_path: str, now_text: str, reports: list[str]) -> int:
    """Each finding's first night on main, kept across nights; one that's gone is forgotten. The oldest's age."""
    now = datetime.datetime.fromisoformat(now_text.replace("Z", "+00:00"))
    try:
        first = load(state_path)
    except (OSError, ValueError):
        first = {}
    seen = {}
    for path in reports:
        report = load(path)
        for finding in findings(report):
            key = f"{image_of(report)} {finding['key']}"
            seen[key] = first.get(key, now.isoformat())
    with open(state_path, "w") as file:
        json.dump(dict(sorted(seen.items())), file, indent=1)
    if not seen:
        return 0
    oldest = min(datetime.datetime.fromisoformat(value) for value in seen.values())
    return max(0, int((now - oldest).total_seconds()))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("report", nargs="?")
    parser.add_argument("--baseline")
    parser.add_argument("--baseline-missing")
    parser.add_argument("--age", nargs="+", metavar=("STATE", "NOW"))
    parser.add_argument("--count")
    arguments = parser.parse_args()

    if arguments.age:
        if len(arguments.age) < 2:
            parser.error("--age STATE NOW REPORT...")
        state, now, *reports = arguments.age
        reports += [arguments.report] if arguments.report else []
        print(age(state, now, reports))
        return 0
    if not arguments.report or (arguments.baseline and arguments.baseline_missing):
        parser.error("one report, and --baseline or --baseline-missing at most")

    report = load(arguments.report)
    baseline = load(arguments.baseline) if arguments.baseline else None
    failing, inherited = gate(report, baseline, arguments.baseline_missing)
    image = image_of(report)
    if arguments.baseline_missing:
        print(f"scan-gate: no baseline from main ({arguments.baseline_missing}): every finding counts")
    for finding in inherited:
        print(f"scan-gate: on main too, not this change's: {finding['text']}")
    for finding in failing:
        print(f"scan-gate: FAIL {finding['text']}")
    if arguments.count:
        with open(arguments.count, "w") as file:
            file.write(f"{len(failing)}\n")
    where = "new against main's last scan" if baseline is not None else "fixable"
    print(f"scan-gate: {image}: {len(failing)} {where} failing, {len(inherited)} inherited from main")
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main())
