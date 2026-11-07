#!/usr/bin/env python3
"""scripts/images/scan-gate.py on made-up Trivy reports (scripts/checks/deploy.sh). Exits non-zero on the first that
went wrong."""
import json
import os
import subprocess
import sys
import tempfile

GATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scan-gate.py")
IMAGE = "ghcr.io/gameboy-21/idleo-control-plane"


def vulnerability(cve, package, severity="HIGH", fixed="1.2"):
    return {"VulnerabilityID": cve, "PkgName": package, "InstalledVersion": "1.0", "FixedVersion": fixed,
            "Severity": severity}


def report(*vulnerabilities, secrets=(), name=IMAGE + "@sha256:" + "a" * 64):
    return {"ArtifactName": name, "Results": [
        {"Target": "image (debian 13.7)", "Vulnerabilities": list(vulnerabilities) or None},
        {"Target": "/app/config.yaml", "Secrets": list(secrets) or None},
    ]}


ZLIB = vulnerability("CVE-2026-85091", "zlib1g")
NEW = vulnerability("CVE-2026-99999", "libssl3t64", severity="CRITICAL")
SECRET = {"RuleID": "github-pat", "Severity": "CRITICAL"}

work = tempfile.mkdtemp()


def write(name, content):
    path = os.path.join(work, name)
    with open(path, "w") as file:
        json.dump(content, file)
    return path


def run(*arguments):
    return subprocess.run([sys.executable, GATE, *arguments], capture_output=True, text=True)


def expect(condition, message):
    if not condition:
        sys.exit(f"scan-gate-test: FAIL: {message}")


count = os.path.join(work, "count")

# Main: every fixable CRITICAL or HIGH fails, and its count is written for the alert.
main_run = run(write("main.json", report(ZLIB)), "--count", count)
expect(main_run.returncode == 1 and "FAIL HIGH CVE-2026-85091 in zlib1g" in main_run.stdout, f"main: {main_run.stdout}")
expect(open(count).read().strip() == "1", "main's count isn't 1")

# Unfixed, MEDIUM or LOW findings never fail, anywhere.
quiet = report(vulnerability("CVE-1", "a", fixed=""), vulnerability("CVE-2", "b", severity="MEDIUM"))
expect(run(write("quiet.json", quiet)).returncode == 0, "an unfixed or MEDIUM finding failed")

# A pull request: what main's last scan already had is inherited, reported, and doesn't fail it.
baseline = write("baseline.json", report(ZLIB, name=IMAGE + "@sha256:" + "b" * 64))
inherited = run(write("pr-same.json", report(ZLIB)), "--baseline", baseline, "--count", count)
expect(inherited.returncode == 0, f"an inherited finding failed the pull request: {inherited.stdout}")
expect("on main too, not this change's: HIGH CVE-2026-85091" in inherited.stdout, "the inherited finding isn't reported")
expect(open(count).read().strip() == "0", "an inherited finding was counted")

# A finding that's new against main fails it, with the inherited one still only reported.
new = run(write("pr-new.json", report(ZLIB, NEW)), "--baseline", baseline)
expect(new.returncode == 1 and "FAIL CRITICAL CVE-2026-99999 in libssl3t64" in new.stdout
       and "on main too" in new.stdout, f"a new finding: {new.stdout}")

# The same id in another package is another finding.
moved = run(write("pr-moved.json", report(vulnerability("CVE-2026-85091", "zlib1g-dev"))), "--baseline", baseline)
expect(moved.returncode == 1, "the same CVE in another package was taken as main's")

# A secret always fails, even one main's scan had too.
with_secret = write("secret-main.json", report(ZLIB, secrets=[SECRET], name=IMAGE + ":main"))
secret = run(write("pr-secret.json", report(ZLIB, secrets=[SECRET])), "--baseline", with_secret)
expect(secret.returncode == 1 and "FAIL a CRITICAL or HIGH secret in /app/config.yaml" in secret.stdout and "github-pat" not in secret.stdout, f"a secret: {secret.stdout}")

# No baseline: fail closed, and say why.
missing = run(write("pr-missing.json", report(ZLIB)), "--baseline-missing", "no main run kept a scan")
expect(missing.returncode == 1 and "no baseline from main (no main run kept a scan)" in missing.stdout,
       f"no baseline: {missing.stdout}")

# A baseline of another image is refused, never compared.
other = write("other.json", report(ZLIB, name="ghcr.io/gameboy-21/idleo-web@sha256:" + "c" * 64))
refused = run(write("pr-other.json", report(ZLIB)), "--baseline", other)
expect(refused.returncode != 0 and "not ghcr.io/gameboy-21/idleo-control-plane's" in refused.stderr,
       f"another image's baseline: {refused.stdout} {refused.stderr}")

# The nightly age: a finding's first night is kept across nights, and a gone one is forgotten.
state = os.path.join(work, "first-seen.json")
night1 = run("--age", state, "2026-11-01T21:37:00Z", write("n1.json", report(ZLIB)))
expect(night1.returncode == 0 and night1.stdout.strip() == "0", f"first night: {night1.stdout} {night1.stderr}")
night8 = run("--age", state, "2026-11-08T21:37:00Z", write("n8.json", report(ZLIB, NEW)))
expect(night8.stdout.strip() == str(7 * 86400), f"a week on, the oldest isn't 7 days old: {night8.stdout}")
saved = json.load(open(state))
expect(saved[f"{IMAGE} CVE-2026-85091 zlib1g"].startswith("2026-11-01"), f"first night not kept: {saved}")
night9 = run("--age", state, "2026-11-09T21:37:00Z", write("n9.json", report(NEW)))
expect(night9.stdout.strip() == str(86400), f"the fixed finding wasn't forgotten: {night9.stdout}")
expect(list(json.load(open(state))) == [f"{IMAGE} CVE-2026-99999 libssl3t64"], "the state kept a fixed finding")
clean = run("--age", state, "2026-11-10T21:37:00Z", write("n10.json", report()))
expect(clean.stdout.strip() == "0" and json.load(open(state)) == {}, "a clean night didn't clear the state")
# A state file that can't be read starts again rather than failing the scan.
with open(state, "w") as file:
    file.write("not json")
expect(run("--age", state, "2026-11-11T21:37:00Z", write("n11.json", report(ZLIB))).stdout.strip() == "0",
       "an unreadable state wasn't started again")

print("scan-gate-test: PASS")
