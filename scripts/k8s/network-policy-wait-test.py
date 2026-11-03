#!/usr/bin/env python3
"""
wait-for-network-policy on its own (ADR 0048): the script a pod's first init container runs, given as the first argument,
against a fake kubelet on a port of this machine. A pod's real kubelet can't be stopped for a moment on its own, since on
k3s it shares its process with everything else on the node. Kube-router's rules leave a blocked pod's try unanswered, so
only silence means they're in place:
  - a port that answers holds the pod, and fails it at the limit;
  - a port that refuses (a kubelet that's down or restarting) never lets it through, however fast it refuses;
  - a port gone silent lets it through after five tries, in about 1.5 s;
  - silent for fewer than five tries, then refusing, starts the count over.
An unprivileged test can't make a port silent on both Linux and macOS (macOS makes room in a full queue), so a stand-in for
timeout gives silence's exit status after its time. That a real pod's try does end that way is check 13's "ends in silence".
Prints what each did; exits non-zero on the first that went wrong.
"""
import os, socket, stat, subprocess, sys, tempfile, time

LIMIT_S = 3
FIVE_SILENT_S = 1.5  # Five tries of 300 ms.
SLOW_S = 3.0  # A loaded runner may be late, but not by a second and a half.
TIMEOUT = """#!/bin/bash
limit=$1; shift
[[ -e "$SILENT" ]] && { sleep "$limit"; exit 124; }
exec "$@"
"""


def listener():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", 0))
    s.listen(64)
    return s


def closed_port():
    s = listener()
    port = s.getsockname()[1]
    s.close()
    return port


def wait_script(path, port, limit_s=300):
    script = open(path).read()
    if "/10250" not in script or "SECONDS + 300" not in script:
        sys.exit(f"{path} doesn't try the kubelet's port with a 300 s limit")
    return script.replace("/10250", f"/{port}").replace("SECONDS + 300", f"SECONDS + {limit_s}")


def fail(message):
    sys.exit(f"network-policy-wait-test: FAIL: {message}")


def main():
    path = sys.argv[1]
    with tempfile.TemporaryDirectory() as tools:
        shim = os.path.join(tools, "timeout")
        with open(shim, "w") as f:
            f.write(TIMEOUT)
        os.chmod(shim, os.stat(shim).st_mode | stat.S_IEXEC)
        silent = os.path.join(tools, "silent")
        env = {**os.environ, "NODE": "127.0.0.1", "PATH": f"{tools}:{os.environ['PATH']}", "SILENT": silent}

        def start(port, limit_s):
            script = wait_script(path, port, limit_s)
            return subprocess.Popen(["bash", "-c", script], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

        def expect_limit(port, what):
            wait = start(port, LIMIT_S)
            try:
                code = wait.wait(timeout=LIMIT_S + 10)
            except subprocess.TimeoutExpired:
                wait.kill()
                fail(f"{what}: it didn't stop at the limit")
            output = wait.stdout.read()
            if code != 1 or "isn't in place" not in output:
                fail(f"{what} ended with exit {code}, not at the limit: {output}")
            print(f"network-policy-wait-test: {what}: held, and the pod fails at the limit")

        kubelet = listener()
        expect_limit(kubelet.getsockname()[1], "a port that answers")
        kubelet.close()

        expect_limit(closed_port(), "a port that refuses")

        open(silent, "w").close()
        began = time.monotonic()
        wait = start(closed_port(), 30)
        try:
            code = wait.wait(timeout=30)
        except subprocess.TimeoutExpired:
            wait.kill()
            fail("a silent port didn't let it through in 30 s")
        took = time.monotonic() - began
        if code != 0:
            fail(f"a silent port: exit {code}: {wait.stdout.read()}")
        if not FIVE_SILENT_S * 0.9 <= took <= SLOW_S:
            fail(f"a silent port let it through after {took:.2f} s, not about {FIVE_SILENT_S} s")
        print(f"network-policy-wait-test: a silent port: through after {took:.2f} s")

        wait = start(closed_port(), 30)
        time.sleep(0.8)  # Two or three silent tries.
        os.remove(silent)
        time.sleep(0.5)
        open(silent, "w").close()
        time.sleep(0.8)  # Two or three more: five in all, but not in a row.
        os.remove(silent)
        time.sleep(FIVE_SILENT_S)
        if wait.poll() is not None:
            fail(f"silent for a moment, twice, with refusals between, let it through: exit {wait.returncode}")
        wait.kill()
        print("network-policy-wait-test: silent for a moment, with refusals between: still held")


main()
