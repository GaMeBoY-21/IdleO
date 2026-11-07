#!/usr/bin/env python3
"""
How a long suite ended, to Grafana Cloud over OTLP (ADR 0050; .github/actions/report-suite): idleo_suite_passed{suite} (1 or 0),
idleo_suite_findings{suite} when FINDINGS_FILE holds a count, and idleo_suite_oldest_finding_seconds{suite} when
OLDEST_FILE holds how long the oldest finding has stood (the nightly scan, ADR 0048). The endpoint and its Authorization header are production's own
(ADR 0036), as repository secrets; until they exist this warns, and the *Missing alerts are what say so.

    report-suite.py SUITE STATUS    STATUS is the job's job.status

A cancelled job says nothing: a newer run took its place, or it ran out of time, and if that keeps happening its *Missing alert
fires. Exits non-zero if the result couldn't be sent, so a suite nobody hears from is at least red.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request


def metric(name, value, suite, now):
    point = {"asInt": str(value), "timeUnixNano": now, "attributes": [{"key": "suite", "value": {"stringValue": suite}}]}
    return {"name": name, "gauge": {"dataPoints": [point]}}


def findings(variable="FINDINGS_FILE", unread=0):
    """The number in the file the variable names; None when it names none. A file that can't be read is `unread`: no
    findings for the count (a suite that died early is red anyway), and nothing at all for the oldest one's age, which
    must never read as a fresh 0 on a night the scan couldn't say."""
    path = os.environ.get(variable)
    if not path:
        return None
    try:
        return int(open(path).read().strip() or 0)
    except (OSError, ValueError):
        return unread


def main():
    suite, status = sys.argv[1:3]
    if status == "cancelled":
        print(f"{suite} was cancelled: nothing to report")
        return
    endpoint, authorization = os.environ.get("ENDPOINT"), os.environ.get("AUTHORIZATION")
    if not endpoint or not authorization:
        print("::warning::GRAFANA_CLOUD_OTLP_ENDPOINT and GRAFANA_CLOUD_OTLP_AUTHORIZATION aren't set: the result isn't reported")
        return
    now = str(time.time_ns())
    metrics = [metric("idleo_suite_passed", 1 if status == "success" else 0, suite, now)]
    count = findings()
    if count is not None:
        metrics.append(metric("idleo_suite_findings", count, suite, now))
    oldest = findings("OLDEST_FILE", unread=None)
    if oldest is not None:
        metrics.append(metric("idleo_suite_oldest_finding_seconds", oldest, suite, now))
    body = {
        "resourceMetrics": [
            {
                "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "long-suites"}}]},
                "scopeMetrics": [{"metrics": metrics}],
            }
        ]
    }
    request = urllib.request.Request(
        f"{endpoint}/v1/metrics",
        data=json.dumps(body).encode(),
        headers={"Authorization": authorization, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30):
            pass
    except (urllib.error.URLError, TimeoutError) as e:
        sys.exit(f"{suite}'s result couldn't be sent: {getattr(e, 'code', None) or getattr(e, 'reason', e)}")
    print(f"{suite}: reported {status}")


if __name__ == "__main__":
    main()
