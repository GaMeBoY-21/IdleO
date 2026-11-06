#!/usr/bin/env python3
"""ops/grafana/apply-rules.sh against a fake Grafana (scripts/checks/ops.sh). Exits non-zero on the first that went wrong."""
import copy
import http.server
import json
import os
import re
import subprocess
import sys
import threading
import urllib.parse

APPLY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apply-rules.sh")
TOKEN = "test-token"
calls = []
state = {}


def fresh():
    state.clear()
    state.update(folders={}, contact_points=[], policy=None, rules={}, tests=[])


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
            state["policy"] = body
            return self.answer(202, {"message": "policies updated"})
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
            state["tests"].append(body)
            return self.answer(200, {"receivers": [{"name": body["receivers"][0]["name"],
                                                    "grafana_managed_receiver_configs": [{"status": "ok"}]}]})
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
             if k not in ("GRAFANA_URL", "GRAFANA_TOKEN", "ALERTS_TO", "GRAFANA_PROMETHEUS_UID")}
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
digest = state["policy"]["routes"][0]
expect(digest["object_matchers"] == [["severity", "=", "digest"]] and digest["repeat_interval"] == "24h",
       "tickets aren't routed to the daily digest")
rules = list(state["rules"].values())
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

# --test sends one alert to the contact point.
test = run("--test", **stack)
expect(test.returncode == 0 and len(state["tests"]) == 1, f"--test failed: {test.stderr}")
expect(state["tests"][0]["receivers"][0]["grafana_managed_receiver_configs"][0]["settings"]["addresses"]
       == "alerts@example.invalid", "the test alert went somewhere else")

# A wrong token is refused, and says which token it needs.
refused = run(**{**stack, "GRAFANA_TOKEN": "wrong"})
expect(refused.returncode == 1 and "alert-rules service account" in refused.stderr, f"a wrong token: {refused.stderr}")

print("rules-test: PASS")
