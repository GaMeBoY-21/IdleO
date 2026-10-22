#!/usr/bin/env python3
"""A job that attacks its sandbox (agent/runner/tests/hostile/). Run as `hostile.py <attack>`.

Each attack prints one line, `RESULT <attack> HELD <detail>` when the sandbox stopped everything it tried, or
`RESULT <attack> BREACH <detail>` naming what got through; the test reads it from the renter's log. Positive controls
(`CONTROL ...`) prove an attack's "no" isn't just a broken environment (the internet really is reachable on 443).
"""
import ctypes
import errno
import os
import socket
import subprocess
import sys
import time


def result(attack, held, detail):
    print(f"RESULT {attack} {'HELD' if held else 'BREACH'} {detail}", flush=True)
    return 0


def run(args, timeout=10):
    try:
        done = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return done.returncode, (done.stdout + done.stderr).strip()
    except Exception as e:  # noqa: BLE001 - a failure to even run is a failure of the attack
        return 127, str(e)


def connect(host, port, timeout=3.0, family=socket.AF_INET):
    """'open' if connected, 'refused' if the host answered no, 'blocked' if nothing came back, 'unreachable'."""
    s = socket.socket(family, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        return "open"
    except socket.timeout:
        return "blocked"
    except ConnectionRefusedError:
        return "refused"
    except OSError as e:
        return "unreachable" if e.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH, errno.EADDRNOTAVAIL, errno.EAFNOSUPPORT) else f"error:{e.errno}"
    finally:
        s.close()


def parallel_connect(targets, timeout=0.9, chunk=36):
    """Connects to every (host, port) from one thread (a job has few processes to spare), `chunk` at a time, each chunk given
    `timeout` seconds, and returns {target: outcome}. A connection that nothing answers sends its SYN again after a second,
    and the sandbox's limit on new connections (50 a second, a burst of 100) counts every SYN, so each connection gets less
    than a second and each chunk the whole of it: 40 a second, each SYN sent once. A 'blocked' then means the rules blocked
    it, never the limit (the harness checks that the limit dropped nothing)."""
    outcomes = {}
    targets = list(targets)
    for start in range(0, len(targets), chunk):
        began = time.monotonic()
        outcomes.update(_connect_together(targets[start : start + chunk], timeout))
        time.sleep(max(0.0, timeout - (time.monotonic() - began)))
    return outcomes


def _connect_together(targets, timeout):
    import select

    pending = {}
    outcomes = {}
    for target in targets:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setblocking(False)
        code = s.connect_ex(target)
        if code in (0, errno.EINPROGRESS):
            pending[s] = target
        elif code in (errno.ENETUNREACH, errno.EHOSTUNREACH, errno.EADDRNOTAVAIL):
            outcomes[target] = "unreachable"
            s.close()
        elif code == errno.ECONNREFUSED:
            outcomes[target] = "refused"
            s.close()
        else:
            outcomes[target] = f"error:{code}"
            s.close()
    deadline = time.monotonic() + timeout
    while pending and time.monotonic() < deadline:
        _, writable, _ = select.select([], list(pending), [], max(0.0, deadline - time.monotonic()))
        for s in writable:
            target = pending.pop(s)
            err = s.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
            if err == 0:
                outcomes[target] = "open"
            elif err == errno.ECONNREFUSED:
                outcomes[target] = "refused"
            elif err in (errno.ENETUNREACH, errno.EHOSTUNREACH):
                outcomes[target] = "unreachable"
            else:
                outcomes[target] = f"error:{err}"
            s.close()
    for s, target in pending.items():
        outcomes[target] = "blocked"
        s.close()
    return outcomes


def gateway():
    with open("/proc/net/route") as f:
        for line in list(f)[1:]:
            fields = line.split()
            if fields[1] == "00000000":
                raw = int(fields[2], 16)
                return ".".join(str((raw >> (8 * i)) & 255) for i in range(4))
    return None


def write_root():
    breaches = []
    for path in ["/hostile-was-here", "/usr/bin/hostile", "/usr/lib/hostile", "/etc/hostile", "/etc/passwd", "/opt/hostile/x", "/var/hostile", "/bin/hostile", "/lib/hostile"]:
        try:
            with open(path, "w") as f:
                f.write("pwned")
            breaches.append(path)
        except OSError:
            pass
    for path in ["/etc/passwd", "/etc/shadow"]:
        try:
            os.truncate(path, 0)
            breaches.append("truncate " + path)
        except OSError:
            pass
    try:
        os.mkdir("/hostile-dir")
        breaches.append("mkdir /hostile-dir")
    except OSError:
        pass
    try:  # the one place it may write: /tmp (a tmpfs) and /work
        with open("/tmp/ok", "w") as f:
            f.write("fine")
        with open("/work/ok", "w") as f:
            f.write("fine")
    except OSError as e:
        return result("write_root", False, f"/tmp or /work isn't writable: {e}")
    return result("write_root", not breaches, "every write outside /tmp and /work failed" if not breaches else f"wrote {breaches}")


def become_root():
    breaches = []
    status = dict(line.split(":\t", 1) for line in open("/proc/self/status").read().splitlines() if ":\t" in line)
    if os.geteuid() == 0:
        breaches.append("already root")
    if status.get("CapEff", "").strip("0") != "":
        breaches.append(f"capabilities {status['CapEff']}")
    if status.get("NoNewPrivs", "").strip() != "1":
        breaches.append("no_new_privs is off")
    if status.get("Seccomp", "").strip() != "2":
        breaches.append(f"seccomp is {status.get('Seccomp')}")
    try:
        os.setuid(0)
        breaches.append("setuid(0) worked")
    except OSError:
        pass
    # A setuid-root program must not give root: no_new_privs makes the kernel ignore the bit.
    code, out = run(["/usr/bin/su", "-c", "id -u", "root"])
    if code == 0 and out.strip() == "0":
        breaches.append("su gave root")
    code, out = run(["/usr/bin/mount", "-t", "tmpfs", "none", "/mnt"])
    if code == 0:
        breaches.append("mount worked")
    code, out = run(["/usr/bin/unshare", "-r", "id", "-u"])
    if code == 0:
        breaches.append(f"unshare -r gave uid {out}")
    code, out = run(["/usr/bin/unshare", "-m", "true"])
    if code == 0:
        breaches.append("unshare -m worked")
    libc = ctypes.CDLL(None, use_errno=True)
    # init_module(NULL, 0, "") and finit_module(-1, "", 0): a module can only be loaded by root with CAP_SYS_MODULE.
    if libc.syscall(175, None, 0, b"") == 0:
        breaches.append("init_module worked")
    if libc.syscall(313, -1, b"", 0) == 0:
        breaches.append("finit_module worked")
    code, out = run(["modprobe", "dummy"])
    if code == 0:
        breaches.append("modprobe worked")
    return result("become_root", not breaches, "uid 10001, no capabilities, no_new_privs, seccomp; every escalation failed" if not breaches else "; ".join(breaches))


def raw_net():
    breaches = []
    for family, kind, proto, name in [(socket.AF_PACKET, socket.SOCK_RAW, 3, "AF_PACKET"), (socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP, "raw ICMP"), (socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_RAW, "raw IP"), (socket.AF_PACKET, socket.SOCK_DGRAM, 0, "AF_PACKET datagram")]:
        try:
            s = socket.socket(family, kind, proto)
            s.close()
            breaches.append(f"{name} socket")
        except OSError:
            pass
    for args in (["ip", "link", "add", "hostile0", "type", "dummy"], ["ip", "link", "set", "eth0", "down"], ["ip", "addr", "add", "10.99.99.99/24", "dev", "eth0"], ["ip", "route", "add", "10.0.0.0/8", "via", "172.30.0.1"], ["iptables", "-L"], ["iptables", "-F"], ["iptables", "-t", "nat", "-A", "POSTROUTING", "-j", "ACCEPT"]):
        code, out = run(args)
        if code == 0:
            breaches.append(" ".join(args))
    try:
        with open("/proc/sys/net/ipv4/ip_forward", "w") as f:
            f.write("0")
        breaches.append("wrote /proc/sys/net")
    except OSError:
        pass
    return result("raw_net", not breaches, "no raw sockets and no network setting could be changed" if not breaches else "; ".join(breaches))


def own_address():
    # The job's own address on its session network (a UDP connect to the gateway sends nothing, and says which address would be used).
    gw = gateway()
    if not gw:
        return None
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect((gw, 53))
        return probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()


def reach_private():
    # The harness names every session network's first container address too, and one of them is this job's own, which
    # answers itself ("refused"): that is no breach, only the job talking to itself.
    mine = own_address()
    extra = [t for t in os.environ.get("SESSION_PARAM_TARGETS", "").split(",") if t and t != mine]
    hosts = ["10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "224.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.0.254", "198.18.0.1", "198.19.255.254", "192.0.2.1", "198.51.100.1", "203.0.113.1"] + extra
    gw = gateway()
    if gw:
        hosts.append(gw)
    targets = [(h, p) for h in hosts for p in (80, 443, 22)]
    # The session's own gateway address answers DNS (the runner's forwarder, ADR 0038) and nothing else: every other TCP port
    # on it is tried, the well-known ones and the ports of the services a machine like this might run.
    sweep = [port for port in range(1, 1025) if port != 53] + [2375, 2376, 3306, 5000, 5432, 6379, 8000, 8080, 8443, 9000, 9090, 9100, 9200, 10250, 27017]
    if gw:
        targets += [(gw, port) for port in sweep]
    outcomes = parallel_connect(targets)
    through = [f"{h}:{p}={o}" for (h, p), o in outcomes.items() if o in ("open", "refused")]
    control = connect("1.1.1.1", 443, 6.0)
    print(f"CONTROL https {control}", flush=True)
    if control != "open":
        return result("reach_private", False, f"inconclusive: the internet isn't reachable on 443 here ({control}), so a block proves nothing")
    # UDP to the gateway: DNS answers (the control that makes silence mean something), nothing else does.
    udp_through = []
    if gw:
        asked = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01"
        for port in (53, 67, 69, 111, 123, 137, 161, 500, 514, 1900, 5353):
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            u.settimeout(1.5)
            try:
                u.sendto(asked, (gw, port))
                u.recvfrom(512)
                udp_through.append(port)
            except OSError:
                pass
            finally:
                u.close()
        print(f"CONTROL gateway-dns {'answered' if 53 in udp_through else 'silent'}", flush=True)
        if 53 not in udp_through:
            return result("reach_private", False, f"inconclusive: the gateway {gw} doesn't answer DNS on 53, so its other ports being silent proves nothing")
    # DNS to anything else on this machine: its other addresses, and the gateways of other sessions' networks (the harness names
    # them in TARGETS), asked all at once; none may answer, though this session's own gateway did.
    elsewhere = sorted({host for host in extra if host != gw})
    sockets = {}
    for host in elsewhere:
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.setblocking(False)
        try:
            u.sendto(asked, (host, 53))
            sockets[u] = host
        except OSError:
            u.close()
    import select

    answered_elsewhere = []
    deadline = time.monotonic() + 2.0
    while sockets and time.monotonic() < deadline:
        ready, _, _ = select.select(list(sockets), [], [], max(0.0, deadline - time.monotonic()))
        for u in ready:
            try:
                u.recvfrom(512)
                answered_elsewhere.append(sockets[u])
            except OSError:
                pass
            sockets.pop(u, None)
            u.close()
    for u in sockets:
        u.close()
    other_udp = [port for port in udp_through if port != 53] + [f"{host}:53" for host in answered_elsewhere]
    held = not through and not other_udp
    return result("reach_private", held, f"{len(targets)} connections to private, link-local, carrier-NAT, multicast, this machine's addresses and every TCP port but 53 on the gateway {gw}, and its UDP ports but 53, and DNS on every other address of this machine, all blocked" if held else f"reached {through} {other_udp}")


def reach_ports():
    targets = []
    for host in ("1.1.1.1", "8.8.8.8", "93.184.216.34", "142.250.80.46"):
        for port in (21, 22, 23, 25, 110, 143, 445, 587, 993, 1433, 3306, 3389, 5432, 6379, 8080, 8443, 9100):
            targets.append((host, port))
    outcomes = parallel_connect(targets)
    through = [f"{h}:{p}={o}" for (h, p), o in outcomes.items() if o in ("open", "refused")]
    control = connect("1.1.1.1", 443, 6.0)
    print(f"CONTROL https {control}", flush=True)
    udp_replies = []
    # DNS straight to a resolver, public or named, is closed: it goes through the runner's forwarder at the gateway (ADR 0038).
    for host, port in (("9.9.9.9", 53), ("1.1.1.1", 53), ("8.8.8.8", 53), ("1.1.1.1", 123), ("8.8.8.8", 123), ("1.1.1.1", 5353)):
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.settimeout(2)
        try:
            u.sendto(b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01", (host, port))
            u.recvfrom(512)
            udp_replies.append(f"{host}:{port}")
        except OSError:
            pass
        finally:
            u.close()
    # The control: the name server this job was given (its gateway) does answer.
    try:
        name = next(line.split()[1] for line in open("/etc/resolv.conf") if line.startswith("nameserver"))
        answer = socket.getaddrinfo("example.com", 80, socket.AF_INET)
        dns = "answered" if answer else "silent"
    except (OSError, StopIteration):
        dns = "silent"
    print(f"CONTROL dns {dns}", flush=True)
    if control != "open":
        return result("reach_ports", False, f"inconclusive: 443 isn't reachable here ({control})")
    if dns != "answered":
        return result("reach_ports", False, "inconclusive: DNS through the runner's forwarder doesn't work, so blocking the rest proves nothing")
    held = not through and not udp_replies
    return result("reach_ports", held, f"{len(targets)} TCP ports on 4 public hosts and 6 UDP services other than HTTPS and DNS through the forwarder were all dropped, direct DNS to 3 resolvers included" if held else f"reached {through} {udp_replies}")


def ipv6():
    breaches = []
    for host in ("2606:4700:4700::1111", "2001:4860:4860::8888", "fe80::1", "::1", "ff02::1"):
        for port in (80, 443, 53):
            outcome = connect(host, port, 3.0, socket.AF_INET6)
            if outcome in ("open", "refused") and host != "::1":
                breaches.append(f"{host}:{port}={outcome}")
    try:
        addresses = [line.split()[0] for line in open("/proc/net/if_inet6")]
    except OSError:
        addresses = []
    global_addresses = [a for a in addresses if not a.startswith(("fe80", "00000000000000000000000000000001"))]
    if global_addresses:
        breaches.append(f"has IPv6 addresses {global_addresses}")
    try:
        socket.getaddrinfo("ipv6.google.com", 80, socket.AF_INET6)
        # resolving is allowed; connecting is what must fail, covered above
    except OSError:
        pass
    return result("ipv6", not breaches, "no IPv6 address and no IPv6 connection to anywhere" if not breaches else "; ".join(breaches))


def fork_bomb():
    children = []
    failed_with = None
    try:
        for _ in range(100_000):
            pid = os.fork()
            if pid == 0:
                time.sleep(60)
                os._exit(0)
            children.append(pid)
    except OSError as e:
        failed_with = e.errno
    count = len(children)
    for pid in children:
        try:
            os.kill(pid, 9)
        except OSError:
            pass
    for pid in children:
        try:
            os.waitpid(pid, 0)
        except OSError:
            pass
    limit = int(os.environ.get("SESSION_PARAM_PIDS_LIMIT", "64"))
    print(f"FORKED {count}", flush=True)
    return result("fork_bomb", failed_with == errno.EAGAIN and count <= limit, f"fork stopped at {count} processes (limit {limit}, errno {failed_with})")


def memory_bomb():
    chunks = []
    try:
        while True:
            block = bytearray(8 * 1024 * 1024)
            for i in range(0, len(block), 4096):
                block[i] = 1
            chunks.append(block)
            print(f"ALLOCATED {len(chunks) * 8} MiB", flush=True)
    except MemoryError:
        return result("memory_bomb", True, f"python was refused memory at {len(chunks) * 8} MiB")


def fill_disk():
    written = 0
    block = b"\x01" * (1024 * 1024)
    try:
        with open("/work/fill", "wb") as f:
            while True:
                f.write(block)
                f.flush()
                os.fsync(f.fileno())
                written += 1
    except OSError as e:
        print(f"RESULT fill_disk HELD wrote {written} MiB, then errno {e.errno}", flush=True)
        sys.exit(1)


def see_grants():
    # Built here, so this script (a readable file in the image) doesn't itself contain what it searches for.
    secrets = [("HOSTILE" + tail).encode() for tail in ("-ACCESS-KEY", "-SECRET-KEY", "-SESSION-TOKEN")]
    found = []
    for k, v in os.environ.items():
        if any(s in (k + v).encode() for s in secrets) or "AWS" in k or "TOKEN" in k.upper() and k.startswith(("AWS", "STORAGE", "S3")):
            found.append(f"env {k}")
    pids = [p for p in os.listdir("/proc") if p.isdigit()]
    for pid in pids:
        for name in ("environ", "cmdline"):
            try:
                data = open(f"/proc/{pid}/{name}", "rb").read()
            except OSError:
                continue
            if any(s in data for s in secrets):
                found.append(f"/proc/{pid}/{name}")
        try:  # a process's memory: only its own and not even that for a child here
            maps = open(f"/proc/{pid}/maps").read()
        except OSError:
            maps = ""
    scanned = 0
    for top in ("/", ):
        for root, dirs, files in os.walk(top):
            if root.startswith(("/proc", "/sys", "/dev")):
                dirs[:] = []
                continue
            for name in files:
                path = os.path.join(root, name)
                try:
                    if os.path.islink(path) or os.path.getsize(path) > 8 * 1024 * 1024:
                        continue
                    data = open(path, "rb").read()
                except OSError:
                    continue
                scanned += 1
                if any(s in data for s in secrets):
                    found.append(path)
    sockets = []
    for path in ("/run/idleo/runner.sock", "/proc/1/root/run/idleo/runner.sock", "/var/run/idleo/runner.sock", "/run/containerd/containerd.sock", "/proc/1/root/run/containerd/containerd.sock", "/var/run/docker.sock"):
        s = socket.socket(socket.AF_UNIX)
        s.settimeout(2)
        try:
            s.connect(path)
            sockets.append(path)
        except OSError:
            pass
        finally:
            s.close()
    mounts = [line.split()[4] for line in open("/proc/self/mountinfo")]
    odd = [m for m in mounts if m.startswith(("/run/idleo", "/var/lib/idleo", "/var/lib/containerd", "/etc/idleo"))]
    breaches = found + [f"connected to {p}" for p in sockets] + [f"mounted {m}" for m in odd]
    return result("see_grants", not breaches, f"searched the environment, {len(pids)} processes and {scanned} files: no grant, no runner or containerd socket" if not breaches else "; ".join(breaches))


def see_other_session():
    # The attacker is given the marker in two halves, so its own environment doesn't contain what it searches for.
    marker = (os.environ.get("SESSION_PARAM_OTHER_MARKER", "") or os.environ.get("SESSION_PARAM_MARKER_A", "") + os.environ.get("SESSION_PARAM_MARKER_B", "")).encode()
    role = os.environ.get("SESSION_PARAM_ROLE", "attacker")
    if role == "victim":
        with open("/work/victim-secret", "w") as f:
            f.write(marker.decode())
        print("VICTIM-READY", flush=True)
        time.sleep(int(os.environ.get("SESSION_PARAM_HOLD", "40")))
        return 0
    found = []
    if not marker:
        return result("see_other_session", False, "no marker given")
    for pid in [p for p in os.listdir("/proc") if p.isdigit()]:
        for name in ("environ", "cmdline"):
            try:
                if marker in open(f"/proc/{pid}/{name}", "rb").read():
                    found.append(f"/proc/{pid}/{name}")
            except OSError:
                pass
    visible = len([p for p in os.listdir("/proc") if p.isdigit()])
    for root, dirs, files in os.walk("/"):
        if root.startswith(("/proc", "/sys", "/dev")):
            dirs[:] = []
            continue
        for name in files:
            path = os.path.join(root, name)
            try:
                if os.path.islink(path) or os.path.getsize(path) > 1024 * 1024:
                    continue
                if marker in open(path, "rb").read():
                    found.append(path)
            except OSError:
                pass
    guesses = ["/var/lib/idleo", "/var/lib/idleo/mnt", "/var/lib/idleo/scratch", "/run/containerd", "/work/../.."]
    reachable = [g for g in guesses if os.path.isdir(g) and os.listdir(g) and g != "/work/../.."]
    victim_ip = os.environ.get("SESSION_PARAM_VICTIM_ADDRESS", "")
    net = connect(victim_ip, 8000, 3.0) if victim_ip else "none"
    breaches = found + [f"lists {g}" for g in reachable] + ([f"reached the other session at {victim_ip}: {net}"] if net in ("open", "refused") else [])
    return result("see_other_session", not breaches, f"{visible} visible processes (its own), none of the other session's files, memory or network" if not breaches else "; ".join(breaches))


def outlive_session():
    pid = os.fork()
    if pid == 0:
        os.setsid()
        for fd in range(3):
            try:
                os.close(fd)
            except OSError:
                pass
        os.execv("/usr/bin/python3", ["python3", "-c", "import time\nwhile True:\n    open('/work/alive','a').write('x')\n    time.sleep(1)  # idleo-hostile-sleeper"])
    time.sleep(1)
    print("STARTED-BACKGROUND", flush=True)
    return result("outlive_session", True, "a background process was started; the test checks it's gone after the session")


def write_inputs():
    breaches = []
    base = "/work/input/data"
    try:
        content = open(f"{base}/hello.txt").read()
        print(f"CONTROL read {content!r}", flush=True)
    except OSError as e:
        return result("write_inputs", False, f"inconclusive: the input can't be read: {e}")
    for action, fn in (
        ("write the file", lambda: open(f"{base}/hello.txt", "w").write("pwned")),
        ("append", lambda: open(f"{base}/hello.txt", "a").write("x")),
        ("create a file", lambda: open(f"{base}/new.txt", "w").write("x")),
        ("make a folder", lambda: os.mkdir(f"{base}/dir")),
        ("delete the file", lambda: os.unlink(f"{base}/hello.txt")),
        ("rename the file", lambda: os.rename(f"{base}/hello.txt", f"{base}/moved.txt")),
        ("chmod the file", lambda: os.chmod(f"{base}/hello.txt", 0o777)),
        ("write beside the inputs", lambda: open("/work/input/new.txt", "w").write("x")),
        ("delete the input folder", lambda: os.rmdir("/work/input")),
    ):
        try:
            fn()
            breaches.append(action)
        except OSError:
            pass
    code, out = run(["/usr/bin/mount", "-o", "remount,rw", "/work/input"])
    if code == 0:
        breaches.append("remounted read-write")
    try:
        if open(f"{base}/hello.txt").read() != content:
            breaches.append("the input changed")
    except OSError as e:
        breaches.append(f"the input disappeared: {e}")
    return result("write_inputs", not breaches, "the input could be read, and nothing could write, create, delete, rename or remount it" if not breaches else "; ".join(breaches))


def run_forever():
    print("RUNNING-FOREVER", flush=True)
    time.sleep(3600)


def leave_marker():
    # A value made here and nowhere else, written where a renter's data lives and printed, so the test can prove it's
    # nowhere in the clear on the distribution's disk once the session has ended (its scratch disk was encrypted). The test
    # learns it from this line (the job's uploaded log), not from anything it passed in.
    import secrets

    marker = "IDLEO-SCRATCH-MARKER-" + secrets.token_hex(16)
    blob = (marker + "\n") * 4096
    for rel in ("outputs/result.txt", "checkpoints/state.bin", "big.bin"):
        path = os.path.join("/work", rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(blob)
    with open("/work/MARKER-WRITTEN", "w") as f:
        f.write(marker)
    print(f"RESULT leave_marker HELD marker={marker}", flush=True)
    # Holds the session open, so the test can try to read the disk while it runs (hostile test 16).
    hold = int(os.environ.get("SESSION_PARAM_HOLD", "0"))
    if hold:
        time.sleep(hold)
    return 0


def connection_flood():
    # Tries to open 1,000 new connections a second for 5 seconds to a public address, as a scan or a flood would; counts how
    # many the sandbox let complete. Each socket is closed 300 ms after it was opened, so a held-back one is not retried for long.
    import selectors

    target = ("1.1.1.1", 443)
    selector = selectors.DefaultSelector()
    opened, attempted, completed = [], 0, 0
    started = time.time()
    next_tick = started
    while time.time() - started < 5.0:
        for _ in range(100):  # every 100 ms
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setblocking(False)
            sock.connect_ex(target)
            attempted += 1
            selector.register(sock, selectors.EVENT_WRITE)
            opened.append((time.time(), sock))
        next_tick += 0.1
        deadline = min(next_tick, time.time() + 0.1)
        while time.time() < deadline:
            for key, _ in selector.select(timeout=max(0.0, deadline - time.time())):
                sock = key.fileobj
                selector.unregister(sock)
                if sock.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) == 0:
                    completed += 1
                sock.close()
                opened = [(t, s) for t, s in opened if s is not sock]
        for t, sock in list(opened):
            if time.time() - t > 0.3:
                try:
                    selector.unregister(sock)
                except (KeyError, ValueError):
                    pass
                sock.close()
                opened.remove((t, sock))
    elapsed = time.time() - started
    for _, sock in opened:
        sock.close()
    print(f"RESULT connection_flood HELD attempted={attempted} completed={completed} in={elapsed:.1f}s", flush=True)
    return 0


def upload_flood():
    # Uploads to a public speed-test address over plain HTTP as fast as it can for 20 seconds, and says how fast that was.
    import http.client

    body = b"\x5a" * 4_000_000
    sent, started = 0, time.time()
    while time.time() - started < 20.0:
        c = http.client.HTTPConnection("speed.cloudflare.com", timeout=60)
        c.request("POST", "/__up", body=body, headers={"Content-Type": "text/plain"})
        c.getresponse().read()
        c.close()
        sent += len(body)
    elapsed = time.time() - started
    print(f"RESULT upload_flood HELD sent={sent} seconds={elapsed:.2f} kbps={sent * 8 / elapsed / 1000:.0f}", flush=True)
    return 0


def write_output():
    # Leaves SESSION_PARAM_MB megabytes of output, for the runner to upload when the job ends.
    mb = int(os.environ.get("SESSION_PARAM_MB", "100"))
    block = os.urandom(1024 * 1024)
    os.makedirs("/work/outputs", exist_ok=True)
    with open("/work/outputs/big.bin", "wb") as f:
        for _ in range(mb):
            f.write(block)
    print(f"RESULT write_output HELD wrote {mb} MiB", flush=True)
    return 0


def lookup_pools():
    # Looks up each name in SESSION_PARAM_DOMAINS (comma separated) the way any program does, and says what came of each.
    for domain in [d for d in os.environ.get("SESSION_PARAM_DOMAINS", "").split(",") if d]:
        try:
            socket.getaddrinfo(domain, 80, socket.AF_INET)
            outcome = "resolved"
        except socket.gaierror as e:
            outcome = f"gaierror:{e.errno}"
        print(f"LOOKUP {domain} {outcome}", flush=True)
        time.sleep(0.5)
    print("RESULT lookup_pools HELD all looked up", flush=True)
    time.sleep(30)
    return 0


def run_named():
    # Runs a copy of `sleep` under the name SESSION_PARAM_NAME: a program that is not what its name says, or is.
    import shutil

    name = os.environ.get("SESSION_PARAM_NAME", "idleo-testminer")
    target = f"/work/{name}"
    shutil.copy("/bin/sleep", target)
    os.chmod(target, 0o755)
    subprocess.Popen([target, "30"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"RESULT run_named HELD started {name}", flush=True)
    time.sleep(30)
    return 0


ATTACKS = {f.__name__: f for f in (write_root, become_root, raw_net, reach_private, reach_ports, ipv6, fork_bomb, memory_bomb, fill_disk, see_grants, see_other_session, outlive_session, write_inputs, run_forever, leave_marker, connection_flood, upload_flood, write_output, lookup_pools, run_named)}

if __name__ == "__main__":
    # `hostile.py <attack>`, or, as the development-only `hostile-test` template runs it, the Attack parameter.
    name = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("SESSION_PARAM_ATTACK", "")
    if name not in ATTACKS:
        print(f"unknown attack {name!r}; one of {sorted(ATTACKS)}", file=sys.stderr)
        sys.exit(2)
    sys.exit(ATTACKS[name]() or 0)
