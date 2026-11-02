#!/usr/bin/env python3
"""
wait-for-network-policy on its own (ADR 0048): the script a pod's first init container runs, given as the first argument,
against a fake kubelet on a port of this machine. A pod's real kubelet can't be stopped for a moment on its own, since on
k3s it shares its process with everything else on the node.
  - a kubelet that stops answering for a moment doesn't end the wait, however many times it does;
  - it ends once the port has failed five times in a row, and not before;
  - a port that never stops answering fails the pod at the limit.
Prints what each did; exits non-zero on the first that went wrong.
"""
import os, re, socket, stat, subprocess, sys, tempfile, time

BLIP_S = 0.2  # At most three failed tries, 100 ms apart: well short of five.
BLIPS = 5
FIVE_TRIES_S = 0.4  # Five failures in a row are four gaps of 100 ms at the least.
LIMIT_S = 2


def listener(port=0):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(64)
    return s


def wait_script(path, port, limit_s=300):
    script = open(path).read()
    if "/10250" not in script or "SECONDS + 300" not in script:
        sys.exit(f"{path} doesn't try the kubelet's port with a 300 s limit")
    return script.replace("/10250", f"/{port}").replace("SECONDS + 300", f"SECONDS + {limit_s}")


def start(script, tools):
    env = {**os.environ, "NODE": "127.0.0.1", "PATH": f"{tools}:{os.environ['PATH']}"}
    return subprocess.Popen(["bash", "-c", script], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def fail(message):
    sys.exit(f"network-policy-wait-test: FAIL: {message}")


def main():
    path = sys.argv[1]
    with tempfile.TemporaryDirectory() as tools:
        # macOS has no timeout; on this machine a closed port refuses at once, so nothing needs one.
        if subprocess.run(["bash", "-c", "command -v timeout"], capture_output=True).returncode != 0:
            shim = os.path.join(tools, "timeout")
            with open(shim, "w") as f:
                f.write('#!/bin/bash\nshift\nexec "$@"\n')
            os.chmod(shim, os.stat(shim).st_mode | stat.S_IEXEC)

        kubelet = listener()
        port = kubelet.getsockname()[1]
        wait = start(wait_script(path, port), tools)
        time.sleep(0.5)
        if wait.poll() is not None:
            fail(f"it ended while the kubelet answered, exit {wait.returncode}")
        for blip in range(BLIPS):
            kubelet.close()
            time.sleep(BLIP_S)
            kubelet = listener(port)
            time.sleep(0.3)
            if wait.poll() is not None:
                fail(f"a kubelet that stopped answering for {BLIP_S} s let the pod through, on blip {blip + 1}")
        print(f"network-policy-wait-test: {BLIPS} blips of {BLIP_S} s: still waiting")

        kubelet.close()
        stopped = time.monotonic()
        try:
            code = wait.wait(timeout=10)
        except subprocess.TimeoutExpired:
            wait.kill()
            fail("it didn't end within 10 s of the port closing for good")
        took = time.monotonic() - stopped
        if code != 0:
            fail(f"it exited {code} once the port closed: {wait.stdout.read()}")
        if took < FIVE_TRIES_S:
            fail(f"it ended {took:.2f} s after the port closed, sooner than five tries 100 ms apart")
        print(f"network-policy-wait-test: closed for good: passed after {took:.2f} s")

        kubelet = listener()
        wait = start(wait_script(path, kubelet.getsockname()[1], LIMIT_S), tools)
        code = wait.wait(timeout=LIMIT_S + 10)
        output = wait.stdout.read()
        kubelet.close()
        if code != 1 or "isn't in place" not in output:
            fail(f"a port that never closed ended with exit {code}: {output}")
        print("network-policy-wait-test: never closed: the pod fails at the limit")


main()
