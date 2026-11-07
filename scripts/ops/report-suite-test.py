#!/usr/bin/env python3
"""scripts/ops/report-suite.py against a fake OTLP endpoint (scripts/checks/ops.sh). Exits non-zero on the first that went wrong."""
import http.server
import json
import os
import subprocess
import sys
import tempfile
import threading

REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report-suite.py")
received = []
answer = [200]


class Fake(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        received.append((self.path, self.headers["Authorization"], json.loads(body)))
        self.send_response(answer[0])
        self.end_headers()

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fake)
threading.Thread(target=server.serve_forever, daemon=True).start()
endpoint = f"http://127.0.0.1:{server.server_port}"


def run(status, **env):
    received.clear()
    clean = {k: v for k, v in os.environ.items() if k not in ("ENDPOINT", "AUTHORIZATION", "FINDINGS_FILE", "OLDEST_FILE")}
    return subprocess.run([sys.executable, REPORT, "hostile", status], env={**clean, **env}, capture_output=True, text=True)


def expect(condition, message):
    if not condition:
        sys.exit(f"report-suite-test: FAIL: {message}")


def figures():
    metrics = received[0][2]["resourceMetrics"][0]["scopeMetrics"][0]["metrics"]
    points = {m["name"]: m["gauge"]["dataPoints"][0] for m in metrics}
    return {name: (p["asInt"], p["attributes"][0]["value"]["stringValue"]) for name, p in points.items()}


secrets = {"ENDPOINT": endpoint, "AUTHORIZATION": "Basic secret-value"}
done = run("success", **secrets)
expect(done.returncode == 0, f"a good send failed: {done.stderr}")
expect(received[0][:2] == ("/v1/metrics", "Basic secret-value"), f"sent to the wrong place: {received[0][:2]}")
expect(figures() == {"idleo_suite_passed": ("1", "hostile")}, f"a pass: {figures()}")

run("failure", **secrets)
expect(figures() == {"idleo_suite_passed": ("0", "hostile")}, f"a failure: {figures()}")

done = run("cancelled", **secrets)
expect(done.returncode == 0 and not received, "a cancelled run must report nothing")

with tempfile.NamedTemporaryFile("w") as file:
    file.write("3\n")
    file.flush()
    run("failure", FINDINGS_FILE=file.name, **secrets)
    expect(figures()["idleo_suite_findings"] == ("3", "hostile"), f"the findings: {figures()}")
run("failure", FINDINGS_FILE="/nonexistent", **secrets)
expect(figures()["idleo_suite_findings"] == ("0", "hostile"), f"no findings file: {figures()}")

# The nightly scan's oldest finding, in seconds, beside the count; nothing of it when the file isn't named.
with tempfile.NamedTemporaryFile("w") as file:
    file.write("604900\n")
    file.flush()
    run("failure", OLDEST_FILE=file.name, **secrets)
    expect(figures()["idleo_suite_oldest_finding_seconds"] == ("604900", "hostile"), f"the oldest: {figures()}")
run("failure", OLDEST_FILE="", **secrets)
expect("idleo_suite_oldest_finding_seconds" not in figures(), f"an unnamed oldest file was reported: {figures()}")
run("failure", OLDEST_FILE="/nonexistent", **secrets)
expect("idleo_suite_oldest_finding_seconds" not in figures(), f"a missing oldest file read as 0: {figures()}")

done = run("failure")
expect(done.returncode == 0 and "::warning::" in done.stdout and not received, "with no secrets it warns and sends nothing")

answer[0] = 500
done = run("success", **secrets)
expect(done.returncode != 0, "a refused send must fail the step")
expect("secret-value" not in done.stdout + done.stderr, "the failure printed the secret")
print("report-suite-test: ok")
