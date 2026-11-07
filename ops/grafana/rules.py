#!/usr/bin/env python3
"""The rules in ops/alerts, in the Grafana Cloud stack's own alerting (ADR 0054). Run through ops/grafana/apply-rules.sh.

Each Prometheus rule becomes a Grafana rule with the same name, labels and annotations. Its query (A) is the rule's
expression, read at one instant; its condition (B) holds for every series A returns, as a Prometheus rule fires for every
series its expression returns, and no series means nothing is wrong. Pages go in one folder and tickets (severity digest)
in another, each group under its name in ops/alerts. Everything has a fixed id and is replaced whole, and what is ours but
no longer in ops/alerts is deleted, so a second run changes nothing.
"""

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
FOLDERS = {"page": ("idleo-pages", "IdleO pages"), "digest": ("idleo-tickets", "IdleO tickets")}
CONTACT_POINT = {"uid": "idleo-email", "name": "IdleO email"}
SUBJECT = "[{{ .Status | toUpper }}] {{ .CommonLabels.alertname }}: {{ .CommonAnnotations.summary }}"
# Every rule says what it watches: env production, the running service (the control plane, the database, backups,
# Valkey, the agents), or env ci, a suite CI runs (each reads idleo_suite_passed, which the suites push themselves).
ENVS = {"production", "ci"}
CI_METRIC = "idleo_suite_passed"
# Before go-live there is no server, so every production rule would fire (ControlPlaneSilent first): while PRE_LAUNCH
# is on (the default), their notifications go through this mute, always on; CI's rules stay live. Turning it off is a
# step of going live (docs/runbooks/deploying.md, "Going live").
MUTE = {"name": "idleo-pre-launch", "time_intervals": [{"times": [{"start_time": "00:00", "end_time": "24:00"}]}]}
# Testing a contact point: Grafana 13 removed the legacy receivers/test endpoint (it answers 410) for this one. The
# receiver "-" is one not saved yet, so the integration is tested exactly as the request gives it.
TEST_PATH = "/apis/notifications.alerting.grafana.app/v1beta1/namespaces/{namespace}/receivers/-/test"


def policy(pre_launch: bool) -> dict:
    """Pages at once and again every 4 hours while they last; tickets once a day (ADR 0036's routing). Production's
    rules have routes of their own, first, so that they can be muted before go-live; routes don't inherit a mute."""
    digest = {"group_wait": "10m", "repeat_interval": "24h"}
    production = [
        {"receiver": CONTACT_POINT["name"], "object_matchers": [["env", "=", "production"], ["severity", "=", "digest"]],
         **digest},
        {"receiver": CONTACT_POINT["name"], "object_matchers": [["env", "=", "production"]]},
    ]
    if pre_launch:
        for route in production:
            route["mute_time_intervals"] = [MUTE["name"]]
    return {
        "receiver": CONTACT_POINT["name"],
        "group_by": ["grafana_folder", "alertname"],
        "group_wait": "30s",
        "group_interval": "5m",
        "repeat_interval": "4h",
        "routes": [
            *production,
            {"receiver": CONTACT_POINT["name"], "object_matchers": [["severity", "=", "digest"]], **digest},
        ],
    }


def pre_launch() -> bool:
    """PRE_LAUNCH: on unless it says off. Anything else is refused, so a typo never unmutes production."""
    value = os.environ.get("PRE_LAUNCH", "on").strip().lower()
    if value in ("on", "1", "true", "yes"):
        return True
    if value in ("off", "0", "false", "no"):
        return False
    raise Refused(f"PRE_LAUNCH is {value!r}; it's on (the default) or off")
# Grafana Cloud evaluates a group once a minute at most.
INTERVAL_SECONDS = 60


class Refused(Exception):
    pass


def uid(alert: str) -> str:
    return "idleo-" + hashlib.sha256(alert.encode()).hexdigest()[:20]


def template(text: str) -> str:
    """Prometheus's {{ $value }} is Grafana's {{ $values.A.Value }}: the query's own number, so humanize still works."""
    return re.sub(r"\$value\b", "$values.A.Value", text)


def grafana_rule(group: str, rule: dict, prometheus: str) -> dict:
    folder = FOLDERS[rule["labels"]["severity"]][0]
    return {
        "uid": uid(rule["alert"]),
        "title": rule["alert"],
        "folderUID": folder,
        "ruleGroup": group,
        "condition": "B",
        "data": [
            {
                "refId": "A",
                "relativeTimeRange": {"from": 600, "to": 0},
                "datasourceUid": prometheus,
                "model": {"refId": "A", "expr": rule["expr"], "instant": True, "range": False},
            },
            {
                "refId": "B",
                "relativeTimeRange": {"from": 0, "to": 0},
                "datasourceUid": "__expr__",
                "model": {"refId": "B", "type": "math", "expression": "is_number($A) || is_nan($A) || is_inf($A)"},
            },
        ],
        "noDataState": "OK",
        "execErrState": "Error",
        "for": rule.get("for", "0s"),
        "labels": rule.get("labels", {}),
        "annotations": {name: template(value) for name, value in rule.get("annotations", {}).items()},
        "isPaused": False,
    }


def wanted_groups(prometheus: str) -> dict[tuple[str, str], list[dict]]:
    """(folder uid, group) -> its rules, from every file in ops/alerts; raises on a rule that can't be loaded."""
    groups: dict[tuple[str, str], list[dict]] = {}
    seen: dict[str, str] = {}
    for path in sorted((ROOT / "ops" / "alerts").glob("*.yaml")):
        for group in yaml.safe_load(path.read_text())["groups"]:
            for rule in group["rules"]:
                name = rule.get("alert")
                if not name:
                    raise Refused(f"{path.name}: group {group['name']} has a recording rule; only alerts are loaded")
                unknown = sorted(set(rule) - {"alert", "expr", "for", "labels", "annotations"})
                if unknown:
                    raise Refused(f"{path.name}: {name} has {', '.join(unknown)}, which the conversion doesn't carry")
                if rule.get("labels", {}).get("severity") not in FOLDERS:
                    raise Refused(f"{path.name}: {name} has no severity page or digest")
                env = rule.get("labels", {}).get("env")
                if env not in ENVS:
                    raise Refused(f"{path.name}: {name} has no env production or ci")
                # A CI rule reads what the suites push; anything else watches the service, and is muted before go-live.
                if (env == "ci") != (CI_METRIC in rule["expr"]):
                    raise Refused(f"{path.name}: {name} is env {env}, but "
                                  + (f"doesn't read {CI_METRIC}" if env == "ci" else f"reads {CI_METRIC}, a CI suite's"))
                if name in seen:
                    raise Refused(f"{path.name}: {name} is also in {seen[name]}; Grafana needs each name once")
                seen[name] = path.name
                converted = grafana_rule(group["name"], rule, prometheus)
                groups.setdefault((converted["folderUID"], group["name"]), []).append(converted)
    return groups


class Grafana:
    def __init__(self, url: str, token: str):
        self.url = url.rstrip("/")
        self.token = token
        self.calls: list[tuple[str, str]] = []

    def call(self, method: str, path: str, body: object = None, missing_ok: bool = False):
        self.calls.append((method, path))
        request = urllib.request.Request(
            self.url + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode()
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            detail = error.read().decode()[:400]
            hint = ""
            if error.code == 401:
                hint = " (is GRAFANA_TOKEN the alert-rules service account's token, and unexpired?)"
            elif error.code == 403:
                hint = (" (the alert-rules service account's role doesn't allow this call: it needs the Admin role,"
                        " docs/runbooks/monitoring.md)")
            raise Refused(f"{method} {path}: {error.code} {detail}{hint}") from None
        return json.loads(text) if text.strip() else None


def prometheus_uid(grafana: Grafana) -> str:
    chosen = os.environ.get("GRAFANA_PROMETHEUS_UID")
    if chosen:
        return chosen
    found = [source for source in grafana.call("GET", "/api/datasources")
             if source.get("type") == "prometheus" and re.fullmatch(r"grafanacloud-.+-prom", source.get("name", ""))]
    if len(found) != 1:
        names = ", ".join(source["name"] for source in found) or "none"
        raise Refused(f"the stack's Prometheus data source isn't one ({names}); set GRAFANA_PROMETHEUS_UID")
    return found[0]["uid"]


def contact_point(address: str) -> dict:
    return {
        **CONTACT_POINT,
        "type": "email",
        "settings": {"addresses": address, "singleEmail": True, "subject": SUBJECT},
        "disableResolveMessage": False,
    }


def apply(grafana: Grafana, address: str, muted: bool) -> str:
    prometheus = prometheus_uid(grafana)
    groups = wanted_groups(prometheus)

    for folder, title in FOLDERS.values():
        if grafana.call("GET", f"/api/folders/{folder}", missing_ok=True) is None:
            grafana.call("POST", "/api/folders", {"uid": folder, "title": title})

    point = contact_point(address)
    existing = grafana.call("GET", "/api/v1/provisioning/contact-points") or []
    if any(entry.get("uid") == point["uid"] for entry in existing):
        grafana.call("PUT", f"/api/v1/provisioning/contact-points/{point['uid']}", point)
    else:
        grafana.call("POST", "/api/v1/provisioning/contact-points", point)
    # The mute exists before a route names it, and goes only once no route does.
    mute_path = f"/api/v1/provisioning/mute-timings/{MUTE['name']}"
    if muted:
        if grafana.call("GET", mute_path, missing_ok=True) is None:
            grafana.call("POST", "/api/v1/provisioning/mute-timings", MUTE)
        else:
            grafana.call("PUT", mute_path, MUTE)
    grafana.call("PUT", "/api/v1/provisioning/policies", policy(muted))
    if not muted and grafana.call("GET", mute_path, missing_ok=True) is not None:
        grafana.call("DELETE", mute_path)

    # A rule that left ops/alerts, or moved to another group or folder, goes first: a rule's id is in one group only.
    wanted = {rule["uid"]: key for key, rules in groups.items() for rule in rules}
    ours = {folder for folder, _ in FOLDERS.values()}
    removed = 0
    for rule in grafana.call("GET", "/api/v1/provisioning/alert-rules") or []:
        if rule.get("folderUID") in ours and wanted.get(rule["uid"]) != (rule["folderUID"], rule["ruleGroup"]):
            grafana.call("DELETE", f"/api/v1/provisioning/alert-rules/{rule['uid']}")
            removed += 1

    for (folder, group), rules in sorted(groups.items()):
        grafana.call(
            "PUT",
            f"/api/v1/provisioning/folder/{folder}/rule-groups/{urllib.parse.quote(group, safe='')}",
            {"title": group, "folderUid": folder, "interval": INTERVAL_SECONDS, "rules": rules},
        )
    production = sum(1 for rules in groups.values() for rule in rules if rule["labels"]["env"] == "production")
    state = (f"; PRE_LAUNCH on: {production} production rules muted, the CI suites' live" if muted
             else "; PRE_LAUNCH off: every rule live")
    return summary(groups) + (f"; {removed} no longer in ops/alerts deleted" if removed else "") + state


def send_test(grafana: Grafana, address: str) -> None:
    """One test alert through the contact point's email integration, as the contact point's Test button sends it."""
    namespace = (grafana.call("GET", "/api/frontend/settings") or {}).get("namespace")
    if not namespace:
        raise Refused("the stack didn't say its API namespace (/api/frontend/settings)")
    point = contact_point(address)
    answer = grafana.call(
        "POST",
        TEST_PATH.format(namespace=urllib.parse.quote(namespace, safe="")),
        {
            "integration": {
                "type": point["type"],
                "version": "v1",
                "settings": point["settings"],
                "disableResolveMessage": point["disableResolveMessage"],
            },
            "alert": {
                "labels": {"alertname": "IdleOTestAlert", "severity": "page"},
                "annotations": {"summary": "A test from ops/grafana/apply-rules.sh: alerts reach this address."},
            },
        },
    )
    if (answer or {}).get("status") != "success":
        raise Refused(f"the test alert wasn't sent: {json.dumps(answer)[:400]}")


def summary(groups: dict[tuple[str, str], list[dict]]) -> str:
    parts = []
    for folder, title in FOLDERS.values():
        mine = [rules for (each, _), rules in groups.items() if each == folder]
        parts.append(f"{sum(map(len, mine))} in {title} ({len(mine)} groups)")
    return "rules: " + ", ".join(parts)


def main(arguments: list[str]) -> int:
    if arguments not in ([], ["--check"], ["--test"]):
        print("usage: ops/grafana/apply-rules.sh [--check | --test]", file=sys.stderr)
        return 2
    try:
        if arguments == ["--check"]:
            print("apply-rules: " + summary(wanted_groups("check")) + ", all converted")
            return 0
        missing = [name for name in ("GRAFANA_URL", "GRAFANA_TOKEN", "ALERTS_TO") if not os.environ.get(name)]
        if missing:
            print(f"apply-rules: set {', '.join(missing)} (docs/runbooks/monitoring.md)", file=sys.stderr)
            return 2
        muted = pre_launch()
        grafana = Grafana(os.environ["GRAFANA_URL"], os.environ["GRAFANA_TOKEN"])
        print("apply-rules: " + apply(grafana, os.environ["ALERTS_TO"], muted))
        if arguments == ["--test"]:
            send_test(grafana, os.environ["ALERTS_TO"])
            print(f"apply-rules: a test alert was sent to {os.environ['ALERTS_TO']}")
        return 0
    except Refused as refusal:
        print(f"apply-rules: {refusal}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
