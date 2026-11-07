#!/usr/bin/env python3
"""ops/grafana/apply-rules.sh against a fake Grafana (scripts/checks/ops.sh). Exits non-zero on the first that went wrong."""
import copy
import http.server
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import threading
import urllib.parse

APPLY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apply-rules.sh")
TOKEN = "test-token"
calls = []
state = {}


def fresh():
    state.clear()
    state.update(folders={}, contact_points=[], policy=None, rules={}, tests=[], mute_timings={})


NAMESPACE = "stacks-123456"


def named_mutes(policy):
    return {name for route in (policy or {}).get("routes", []) for name in route.get("mute_time_intervals", [])}


class Fake(http.server.BaseHTTPRequestHandler):
    def answer(self, status, body=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_any(self, method):
        path = urllib.parse.unquote(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length)) if length else None
        calls.append((method, path))
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self.answer(401, {"message": "invalid API key"})
        if (method, path) == ("GET", "/api/datasources"):
            return self.answer(200, [{"uid": "prom-uid", "name": "grafanacloud-idleo-prom", "type": "prometheus"},
                                     {"uid": "logs-uid", "name": "grafanacloud-idleo-logs", "type": "loki"}])
        if method == "GET" and (match := re.fullmatch(r"/api/folders/([\w-]+)", path)):
            folder = state["folders"].get(match[1])
            return self.answer(200, folder) if folder else self.answer(404, {"message": "folder not found"})
        if (method, path) == ("POST", "/api/folders"):
            state["folders"][body["uid"]] = body
            return self.answer(200, body)
        if (method, path) == ("GET", "/api/v1/provisioning/contact-points"):
            return self.answer(200, state["contact_points"])
        if (method, path) == ("POST", "/api/v1/provisioning/contact-points"):
            state["contact_points"].append(body)
            return self.answer(202, body)
        if method == "PUT" and (match := re.fullmatch(r"/api/v1/provisioning/contact-points/([\w-]+)", path)):
            state["contact_points"] = [body if point["uid"] == match[1] else point for point in state["contact_points"]]
            return self.answer(202, body)
        if (method, path) == ("PUT", "/api/v1/provisioning/policies"):
            names = {point["name"] for point in state["contact_points"]}
            if body["receiver"] not in names:
                return self.answer(400, {"message": "receiver does not exist"})
            if named_mutes(body) - set(state["mute_timings"]):
                return self.answer(400, {"message": "mute time interval does not exist"})
            state["policy"] = body
            return self.answer(202, {"message": "policies updated"})
        if (method, path) == ("POST", "/api/v1/provisioning/mute-timings"):
            if body["name"] in state["mute_timings"]:
                return self.answer(409, {"message": "mute timing already exists"})
            state["mute_timings"][body["name"]] = body
            return self.answer(201, body)
        if match := re.fullmatch(r"/api/v1/provisioning/mute-timings/([\w-]+)", path):
            held = state["mute_timings"].get(match[1])
            if method == "GET":
                return self.answer(200, held) if held else self.answer(404, {"message": "mute timing not found"})
            if method == "PUT":
                if not held:
                    return self.answer(404, {"message": "mute timing not found"})
                state["mute_timings"][match[1]] = body
                return self.answer(202, body)
            if method == "DELETE":
                if match[1] in named_mutes(state["policy"]):
                    return self.answer(409, {"message": "mute timing is used by a notification policy"})
                state["mute_timings"].pop(match[1], None)
                return self.answer(204)
        if (method, path) == ("GET", "/api/frontend/settings"):
            return self.answer(200, {"namespace": NAMESPACE, "appSubUrl": ""})
        if (method, path) == ("GET", "/api/v1/provisioning/alert-rules"):
            return self.answer(200, list(state["rules"].values()))
        if method == "DELETE" and (match := re.fullmatch(r"/api/v1/provisioning/alert-rules/([\w-]+)", path)):
            state["rules"].pop(match[1], None)
            return self.answer(204)
        if method == "PUT" and (match := re.fullmatch(r"/api/v1/provisioning/folder/([\w-]+)/rule-groups/(.+)", path)):
            folder, group = match[1], match[2]
            if folder not in state["folders"]:
                return self.answer(404, {"message": "folder not found"})
            for rule in body["rules"]:
                held = state["rules"].get(rule["uid"])
                if held and (held["folderUID"], held["ruleGroup"]) != (folder, group):
                    return self.answer(400, {"message": f"rule {rule['uid']} is in another group"})
            state["rules"] = {uid: rule for uid, rule in state["rules"].items()
                              if (rule["folderUID"], rule["ruleGroup"]) != (folder, group)}
            state["rules"].update({rule["uid"]: copy.deepcopy(rule) for rule in body["rules"]})
            return self.answer(200, body)
        if (method, path) == ("POST", "/api/alertmanager/grafana/config/api/v1/receivers/test"):
            # Removed in Grafana 13, as the real stack answers now.
            return self.answer(410, {"message": "Gone"})
        if (method, path) == (
                "POST", f"/apis/notifications.alerting.grafana.app/v1beta1/namespaces/{NAMESPACE}/receivers/-/test"):
            if not body.get("alert") or not body.get("integration"):
                return self.answer(400, {"message": "alert and integration are required"})
            state["tests"].append(body)
            return self.answer(200, {"apiVersion": "notifications.alerting.grafana.app/v1beta1",
                                     "kind": "CreateReceiverIntegrationTest", "status": "success", "duration": "12ms"})
        return self.answer(404, {"message": f"no {method} {path} in the fake"})

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")

    def do_PUT(self):
        self.handle_any("PUT")

    def do_DELETE(self):
        self.handle_any("DELETE")

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fake)
threading.Thread(target=server.serve_forever, daemon=True).start()
url = f"http://127.0.0.1:{server.server_port}"


def run(*arguments, **env):
    calls.clear()
    clean = {k: v for k, v in os.environ.items()
             if k not in ("GRAFANA_URL", "GRAFANA_TOKEN", "ALERTS_TO", "GRAFANA_PROMETHEUS_UID", "PRE_LAUNCH")}
    return subprocess.run([APPLY, *arguments], env={**clean, **env}, capture_output=True, text=True)


def expect(condition, message):
    if not condition:
        sys.exit(f"rules-test: FAIL: {message}")


stack = {"GRAFANA_URL": url, "GRAFANA_TOKEN": TOKEN, "ALERTS_TO": "alerts@example.invalid"}

# Checking needs nothing from outside.
checked = run("--check")
expect(checked.returncode == 0 and "all converted" in checked.stdout, f"--check failed: {checked.stderr}")

# Nothing is sent without the token, the stack and the address.
fresh()
unset = run(GRAFANA_URL=url)
expect(unset.returncode == 2 and "GRAFANA_TOKEN" in unset.stderr and not calls, "ran without GRAFANA_TOKEN")

# The first run makes the folders, the contact point, the routing and every rule.
first = run(**stack)
expect(first.returncode == 0, f"the first run failed: {first.stderr}")
expect(set(state["folders"]) == {"idleo-pages", "idleo-tickets"}, f"folders: {sorted(state['folders'])}")
expect([point["settings"]["addresses"] for point in state["contact_points"]] == ["alerts@example.invalid"],
       "the contact point isn't the one address")
def route(labels):
    """The first route whose matchers all hold, as Grafana picks it, or the root."""
    for each in state["policy"]["routes"]:
        if all(labels.get(name) == value for name, _, value in each["object_matchers"]):
            return each
    return state["policy"]


rules = list(state["rules"].values())
by_name = {rule["title"]: rule for rule in rules}
expect(all(rule["labels"].get("env") in ("production", "ci") for rule in rules), "a rule has no env")
expect(route({"severity": "digest", "env": "ci"})["repeat_interval"] == "24h"
       and route({"severity": "digest", "env": "production"})["repeat_interval"] == "24h",
       "tickets aren't routed to the daily digest")
expect(route({"severity": "page", "env": "production"}).get("repeat_interval", "4h") == "4h", "pages aren't every 4 h")
# PRE_LAUNCH on by default: every production rule goes through the mute, every CI suite's rule stays live.
expect(set(state["mute_timings"]) == {"idleo-pre-launch"}, f"mute timings: {sorted(state['mute_timings'])}")
expect(state["mute_timings"]["idleo-pre-launch"]["time_intervals"]
       == [{"times": [{"start_time": "00:00", "end_time": "24:00"}]}], "the pre-launch mute isn't always on")
for rule in rules:
    muted = "idleo-pre-launch" in route(rule["labels"]).get("mute_time_intervals", [])
    expect(muted == (rule["labels"]["env"] == "production"), f"{rule['title']} ({rule['labels']}) muted: {muted}")
for name in ("ControlPlaneSilent", "DatabaseStandbyBehind", "NoBackup", "AgentsDropped", "LedgerChecksSilent"):
    expect(by_name[name]["labels"]["env"] == "production", f"{name} isn't production's")
for name in ("HostileSuiteFailing", "NightlyScanFailing", "FailoverSuiteMissing", "K8sSuiteFailing",
             "GitopsSuiteFailing", "SecuritySuiteFailing", "E2eSuiteFailing", "WebBrowserSuiteFailing",
             "BudgetsSuiteFailing"):
    expect(by_name[name]["labels"]["env"] == "ci", f"{name} isn't a CI suite's")
expect("production rules muted" in first.stdout, f"the run doesn't say production is muted: {first.stdout}")
expect(len(rules) > 50, f"only {len(rules)} rules loaded")
expect(all(rule["folderUID"] == ("idleo-pages" if rule["labels"]["severity"] == "page" else "idleo-tickets")
           for rule in rules), "a page and a ticket share a folder")
expect(all(rule["data"][0]["datasourceUid"] == "prom-uid" for rule in rules), "a rule doesn't query the stack's Prometheus")
expect(all(rule["noDataState"] == "OK" for rule in rules), "no data fires a rule, which a Prometheus rule never does")
summaries = [rule["annotations"]["summary"] for rule in rules]
expect(not any(re.search(r"\$value\b", text) for text in summaries), "a summary still reads Prometheus's $value")
expect(any("$values.A.Value | humanizePercentage" in text for text in summaries), "$value wasn't kept for humanize")
loaded = copy.deepcopy(state)

# The second run changes nothing: no folder, contact point or rule is made or deleted, and the stack is the same.
second = run(**stack)
expect(second.returncode == 0, f"the second run failed: {second.stderr}")
expect(not [call for call in calls if call[0] in ("POST", "DELETE")], f"the second run made or deleted: {calls}")
expect(state == loaded, "the second run changed the stack")

# A rule that is ours but no longer in ops/alerts is deleted; one in someone else's folder is left alone.
gone = {**rules[0], "uid": "idleo-gone", "title": "GoneAlert"}
theirs = {**rules[0], "uid": "theirs", "title": "TheirAlert", "folderUID": "someone-else", "ruleGroup": "theirs"}
state["rules"].update({"idleo-gone": gone, "theirs": theirs})
# And one that moved group: still in ops/alerts, so it comes back in its own group.
moved = next(rule for rule in rules if rule["folderUID"] == "idleo-tickets")
state["rules"][moved["uid"]] = {**moved, "ruleGroup": "an-old-group"}
third = run(**stack)
expect(third.returncode == 0, f"the third run failed: {third.stderr}")
expect("idleo-gone" not in state["rules"] and "theirs" in state["rules"], "the stale rule wasn't the only one deleted")
expect(state["rules"][moved["uid"]]["ruleGroup"] == moved["ruleGroup"], "the moved rule didn't come back in its group")

# --test sends one alert through the contact point's email integration, on the endpoint that replaced the legacy one.
test = run("--test", **stack)
expect(test.returncode == 0 and len(state["tests"]) == 1, f"--test failed: {test.stderr}")
sent = state["tests"][0]
expect(sent["integration"]["type"] == "email" and sent["integration"]["version"] == "v1",
       f"the test isn't the email integration: {sent['integration']}")
expect(sent["integration"]["settings"]["addresses"] == "alerts@example.invalid", "the test alert went somewhere else")
expect(sent["alert"]["labels"]["alertname"] == "IdleOTestAlert", "the test alert isn't named")
expect(("POST", "/api/alertmanager/grafana/config/api/v1/receivers/test") not in calls, "--test called the removed endpoint")

# PRE_LAUNCH off (going live): nothing is muted, and the mute itself goes once no route names it.
live = run(**stack, PRE_LAUNCH="off")
expect(live.returncode == 0, f"PRE_LAUNCH=off failed: {live.stderr}")
expect(not named_mutes(state["policy"]) and not state["mute_timings"], "a mute is left after PRE_LAUNCH=off")
expect(route({"severity": "page", "env": "production"}).get("mute_time_intervals") is None, "production is still muted")
expect("every rule live" in live.stdout, f"the run doesn't say every rule is live: {live.stdout}")
# And on again, it comes back.
again = run(**stack, PRE_LAUNCH="on")
expect(again.returncode == 0 and "idleo-pre-launch" in state["mute_timings"]
       and named_mutes(state["policy"]) == {"idleo-pre-launch"}, f"PRE_LAUNCH=on didn't mute again: {again.stderr}")

# A PRE_LAUNCH that is neither on nor off is refused before anything is sent: a typo never unmutes production.
typo = run(**stack, PRE_LAUNCH="of")
expect(typo.returncode == 1 and "PRE_LAUNCH" in typo.stderr and not calls, f"PRE_LAUNCH=of: {typo.stderr}")

# A rule without an env, or one that says ci without reading the suites' metric, is refused by the conversion.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rules as converter  # noqa: E402

for labels, expr, refusal in (
        ({"severity": "page"}, 'up{job="control-plane"}', "no env"),
        ({"severity": "page", "env": "ci"}, 'up{job="control-plane"}', "doesn't read an idleo_suite_ metric"),
        ({"severity": "digest", "env": "production"}, 'idleo_suite_passed{suite="e2e"} == 0', "a CI suite's")):
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "ops", "alerts"))
        with open(os.path.join(root, "ops", "alerts", "bad.yaml"), "w") as out:
            json.dump({"groups": [{"name": "bad", "rules": [{"alert": "Bad", "expr": expr, "labels": labels}]}]}, out)
        converter.ROOT = pathlib.Path(root)
        try:
            converter.wanted_groups("check")
            expect(False, f"a rule with {labels} on {expr} was converted")
        except converter.Refused as error:
            expect(refusal in str(error), f"refused for another reason: {error}")

# A wrong token is refused, and says which token it needs.
refused = run(**{**stack, "GRAFANA_TOKEN": "wrong"})
expect(refused.returncode == 1 and "alert-rules service account" in refused.stderr, f"a wrong token: {refused.stderr}")

print("rules-test: PASS")
