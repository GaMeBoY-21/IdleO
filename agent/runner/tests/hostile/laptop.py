#!/usr/bin/env python3
"""The 14 hostile tests (ADR 0035) through the real path, on one linked host: the stack, its agent, the job runner inside the
Linux distribution and a container, the way a renter's job reaches a laptop. `run.sh --host <host id>` runs it.

Each test is a session of the development-only `hostile-test` template (templates/development-real.yaml, hidden) running the
hostile image with one attack named by its Attack parameter, booked as the key's owner and read back through the API: how the
session ended, and the attack's own `RESULT` line in its log. Printing the same 14-row table as the Mac's run. What this path
can't see (the Linux distribution's own processes and disk, a second session's address) is marked N/A with the reason; a row
is never PASS by default. A single FAIL is a failed run (exit 1).
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

# (number, what it tries, the attack's name in hostile.py)
TESTS = [
    (1, "write to /, /usr or /etc", "write_root"),
    (2, "become root", "become_root"),
    (3, "raw sockets and network settings", "raw_net"),
    (4, "reach private ranges, this machine, other sessions' networks and every port but DNS on the gateway", "reach_private"),
    (5, "reach anything but TCP 80/443 and DNS through the runner's forwarder", "reach_ports"),
    (6, "IPv6", "ipv6"),
    (7, "fork bomb", "fork_bomb"),
    (8, "memory bomb", "memory_bomb"),
    (9, "fill the disk", "fill_disk"),
    (10, "see the storage grants or the runner's socket", "see_grants"),
    (11, "see another session's scratch, processes or network", "see_other_session"),
    (12, "outlive the session", "outlive_session"),
    (13, "write into the read-only inputs", "write_inputs"),
    (14, "run past max_runtime", "run_forever"),
]
NOT_HERE = {
    11: "needs two sessions at once and the other one's address inside the Linux distribution; only the Mac's run (run.sh with no --host) proves it",
}
TEMPLATE = "hostile-test"
HELLO = b"hello from the renter\n"


class Stack:
    def __init__(self, api, key, cli):
        self.api, self.key, self.cli = api.rstrip("/"), key, cli

    def call(self, method, path, body=None):
        request = urllib.request.Request(
            self.api + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {self.key}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as answer:
                text = answer.read().decode()
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{method} {path} answered {e.code}: {e.read().decode()[:300]}")

    def book(self, attack, input_path=None, max_runtime=None):
        command = [self.cli, "--json", "run", TEMPLATE, "--param", f"attack={attack}", "--max-spend", "5", "--detach"]
        if input_path:
            command += ["--input", f"data={input_path}"]
        if max_runtime:
            command += ["--max-runtime", max_runtime]
        done = subprocess.run(command, capture_output=True, text=True, env={**os.environ, "IDLEO_API_URL": self.api, "IDLEO_API_KEY": self.key})
        if done.returncode != 0:
            raise SystemExit(f"booking {attack} failed ({done.returncode}): {(done.stderr or done.stdout).strip()[:400]}")
        shown = json.loads(done.stdout)
        return shown.get("id") or shown["session"]["id"]

    def settled(self, session, within):
        deadline = time.time() + within
        while time.time() < deadline:
            seen = self.call("GET", f"/v1/sessions/{session}")
            if seen["settled"]:
                return seen
            time.sleep(2)
        raise SystemExit(f"session {session} didn't settle within {within} s (it's {seen['state']})")

    def log(self, session):
        text, cursor = "", None
        while True:
            page = self.call("GET", f"/v1/sessions/{session}/logs?limit=100" + (f"&cursor={cursor}" if cursor else ""))
            text += "".join(chunk["text"] for chunk in page["chunks"])
            cursor = page.get("nextCursor")
            if page.get("complete") or not page["chunks"]:
                return text

    def attack(self, attack, input_path=None, max_runtime=None, within=600):
        session = self.book(attack, input_path, max_runtime)
        ended = self.settled(session, within)
        return ended, self.log(session)


def verdict(log, attack):
    for line in log.splitlines():
        if line.startswith(f"RESULT {attack} "):
            rest = line[len(f"RESULT {attack} "):]
            kind, _, detail = rest.partition(" ")
            return kind == "HELD", detail
    return None


def row(number, name, outcome, detail):
    print(f"| {number:02d} | {name} | {outcome} {detail} |")
    return outcome


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True, help="the host's id: the linked laptop, from the Hosting screen")
    ap.add_argument("--api", default=os.environ.get("IDLEO_API_URL", "http://localhost:8080"))
    ap.add_argument("--cli", default=os.path.join(os.path.dirname(__file__), "../../../../cli/target/release/idleo"))
    args = ap.parse_args()
    key = os.environ.get("IDLEO_API_KEY")
    if not key:
        raise SystemExit("IDLEO_API_KEY names a full API key of the host's owner (Account, API keys, in the web app)")
    stack = Stack(args.api, key, os.path.abspath(args.cli))

    hosts = stack.call("GET", "/v1/hosts")["hosts"]
    chosen = next((h for h in hosts if h["id"] == args.host), None)
    if chosen is None:
        raise SystemExit(f"host {args.host} isn't one of this key's owner's machines")
    if chosen["status"] != "active" or not chosen["online"]:
        raise SystemExit(f"host {chosen['name']} is {chosen['status']} and {'online' if chosen['online'] else 'offline'}: activate it and start its agent first")
    # The scheduler picks the cheapest host that fits, so the others are paused for the run, and resumed after.
    others = [h for h in hosts if h["id"] != args.host and h["status"] == "active" and not h["paused"]]
    for other in others:
        stack.call("POST", f"/v1/hosts/{other['id']}/pause", {"until": None})
    print(f"hostile: running on {chosen['name']} ({args.host}); paused {len(others)} other machine(s) until it's done", file=sys.stderr)

    fails = passes = nas = 0
    try:
        with tempfile.TemporaryDirectory() as scratch:
            hello = os.path.join(scratch, "hello.txt")
            with open(hello, "wb") as f:
                f.write(HELLO)
            print("| # | Attempt | Result |\n| --- | --- | --- |")
            for number, name, attack in TESTS:
                if number in NOT_HERE:
                    row(number, name, "N/A", NOT_HERE[number])
                    nas += 1
                    continue
                ended, log = stack.attack(
                    attack, input_path=hello if number == 13 else None, max_runtime="30s" if number == 14 else None
                )
                if ended.get("hostId") not in (None, args.host):
                    outcome, detail = "FAIL", f"ran on host {ended['hostId']}, not the chosen one"
                else:
                    outcome, detail = judge(stack, number, attack, ended, log)
                row(number, name, outcome, detail)
                passes += outcome == "PASS"
                fails += outcome == "FAIL"
    finally:
        for other in others:
            stack.call("POST", f"/v1/hosts/{other['id']}/resume")
    print(f"\nhostile (laptop): {passes + fails + nas} rows, {passes} PASS, {nas} N/A, {fails} FAIL")
    sys.exit(1 if fails or passes + fails + nas != 14 else 0)


LEFT = "idleo-runner (development): left from finished sessions: "
NOTHING_LEFT = "idleo-runner (development): nothing is left from finished sessions"


def judge_outlive(stack, ended, log):
    """Row 12: the attack starts a background process and ends; the next session's log says what the runner found left."""
    if ended["state"] != "COMPLETED" or "STARTED-BACKGROUND" not in log:
        return "FAIL", f"the attack didn't run as it should (ended {ended['state']}): {log[-200:]!r}"
    after, after_log = stack.attack("write_root")
    if after["state"] != "COMPLETED":
        return "FAIL", f"the session after it ended {after['state']}: {after_log[-200:]!r}"
    if LEFT in after_log:
        return "FAIL", "left behind: " + after_log.split(LEFT, 1)[1].splitlines()[0]
    if NOTHING_LEFT not in after_log:
        return "FAIL", "the runner didn't say what was left: it isn't a development runner (no /etc/idleo/development), so this can't be proved from here"
    return "PASS", "a background process was started; the next session's runner found no container, process, mount, loop device, scratch file or session network left"


def judge(stack, number, attack, ended, log):
    state, code = ended["state"], ended.get("errorCode")
    if number == 12:
        return judge_outlive(stack, ended, log)
    seen = verdict(log, attack)
    if number == 8:  # the container dies of memory, and the session says so
        return ("PASS", f"ended {state} with {code}") if state == "FAILED" and code == "out_of_memory" else ("FAIL", f"ended {state} with {code}; log tail: {log[-200:]!r}")
    if number == 14:
        # The control plane's own limit ends it at the booking's maximum runtime (`max_runtime_exceeded`); the runner's, `timed_out`.
        stopped = state == "FAILED" and code in ("max_runtime_exceeded", "timed_out")
        return ("PASS", f"ended {state} with {code}") if stopped else ("FAIL", f"ended {state} with {code}")
    if number == 9:
        wrote = next((line for line in log.splitlines() if line.startswith("RESULT fill_disk HELD wrote ")), "")
        if state == "FAILED" and code == "scratch_full" and wrote:
            return "PASS", f"ended scratch_full; {wrote.split('HELD ', 1)[1]}; the host's own free space: not checked (the distribution's disk image grows on Windows)"
        return "FAIL", f"ended {state} with {code}; {wrote or 'no verdict'}"
    if state != "COMPLETED":
        return "FAIL", f"the session ended {state} with {code}; log tail: {log[-300:]!r}"
    if seen is None:
        return "FAIL", f"the attack gave no verdict; log tail: {log[-300:]!r}"
    held, detail = seen
    if not held:
        return "FAIL", f"BREACH: {detail}"
    if number == 5 and "CONTROL https open" not in log:
        return "FAIL", "inconclusive: the positive control (HTTPS to a public address) didn't connect"
    if number == 13 and "CONTROL read 'hello from the renter\\n'" not in log:
        return "FAIL", "the input wasn't staged for the attack to try"
    if number == 4:
        detail += "; the gateway and the VM's own addresses: the host's self-test (network_lockdown, scripts/install-report.sh) probes them"
    if number == 7:
        # The runner and the distribution stay healthy: the next session runs.
        after, _ = stack.attack("write_root")
        if after["state"] != "COMPLETED":
            return "FAIL", f"after the fork bomb the next session ended {after['state']}"
        detail += "; the next session ran"
    return "PASS", detail


if __name__ == "__main__":
    main()
