# The hostile job image (ADR 0035, agent/runner/tests/hostile/README.md): a job that tries to break out of the sandbox, one
# attack at a time. It has what an attacker would want: Python with raw sockets and ctypes, iproute2 and iptables to change
# the network, util-linux's mount and unshare, and the setuid programs of an ordinary Ubuntu. It runs as nobody in particular:
# the runner starts it as 10001:10001 whatever the image says.
#
# FOR TESTS ONLY, never published: scripts/ never pushes it anywhere but the development registry.
FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends python3 iproute2 iptables util-linux kmod; \
    rm -rf /var/lib/apt/lists/*
COPY hostile.py /opt/hostile/hostile.py
ENTRYPOINT ["python3", "-u", "/opt/hostile/hostile.py"]
