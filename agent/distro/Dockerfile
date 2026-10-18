# The sealed Linux image the Windows installer will import into WSL2 (docs/plan/host-experience.md §2, ADR 0032's round):
# our own distribution, built here, never the Store's and never downloaded inside at install time. Minimal Ubuntu 24.04,
# containerd and the NVIDIA container toolkit at pinned versions, the lockdown (wsl.conf), a placeholder where the job
# runner will go, and an unprivileged `idleo` user the jobs run as. No SSH server, no compiler, no sudo.
#
# Always built for linux/amd64 (scripts/build-distro.sh): the laptops are x86-64 whatever machine builds this.
# The base is pinned by digest, the packages by version, and the NVIDIA repository's key by fingerprint, so a build
# that can't get exactly these fails instead of changing quietly. To move one forward, change the pin, build, and run
# agent/distro/test.sh.

# ubuntu:24.04 (the multi-architecture index), 2026-09-30.
FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac

ARG CONTAINERD_VERSION=2.2.1-0ubuntu1~24.04.3
ARG RUNC_VERSION=1.3.4-0ubuntu1~24.04.1
ARG SYSTEMD_VERSION=255.4-1ubuntu8.18
ARG NVIDIA_TOOLKIT_VERSION=1.20.1-1
# The fingerprint of NVIDIA's repository signing key (public): the build refuses any other.
ARG NVIDIA_REPOSITORY_SIGNER=C95B321B61E88C1809C4F759DDCAE044F796ECB0
ARG IDLEO_DISTRO_VERSION

ENV DEBIAN_FRONTEND=noninteractive

# The tools used to add NVIDIA's repository are removed again in the same layer.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg; \
    curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey -o /tmp/nvidia.asc; \
    test "$(gpg --show-keys --with-colons /tmp/nvidia.asc | awk -F: '/^fpr/ {print $10; exit}')" = "${NVIDIA_REPOSITORY_SIGNER}"; \
    gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg /tmp/nvidia.asc; \
    echo "deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://nvidia.github.io/libnvidia-container/stable/deb/amd64 /" \
        > /etc/apt/sources.list.d/nvidia-container-toolkit.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        "systemd=${SYSTEMD_VERSION}" "systemd-sysv=${SYSTEMD_VERSION}" \
        "containerd=${CONTAINERD_VERSION}" "runc=${RUNC_VERSION}" \
        "nvidia-container-toolkit=${NVIDIA_TOOLKIT_VERSION}" \
        "nvidia-container-toolkit-base=${NVIDIA_TOOLKIT_VERSION}" \
        "libnvidia-container-tools=${NVIDIA_TOOLKIT_VERSION}" \
        "libnvidia-container1=${NVIDIA_TOOLKIT_VERSION}"; \
    apt-get purge -y --auto-remove curl gnupg; \
    rm -rf /tmp/nvidia.asc /var/lib/apt/lists/* /var/cache/apt/* /var/log/apt /var/log/dpkg.log /var/log/alternatives.log \
        /usr/share/doc /usr/share/man /usr/share/info /usr/share/lintian /root/.gnupg

# The user jobs run as: a system account with no login shell, not in any group that grants anything.
RUN useradd --system --create-home --home-dir /var/lib/idleo --shell /usr/sbin/nologin --user-group idleo

# The lockdown, the same file Phase 0 proves on a real laptop; the proof script beside it, with what it sources.
COPY wsl.conf /etc/wsl.conf
COPY verify-lockdown.sh gpu-common.sh /usr/local/share/idleo/
RUN chmod 0755 /usr/local/share/idleo/verify-lockdown.sh
COPY runner-placeholder.sh /usr/local/bin/idleo-runner

# The GPU-check image (gpu-check.Dockerfile), imported into containerd on the first boot by idleo-images.service.
COPY gpu-check.tar /opt/idleo/images/gpu-check.tar
COPY import-images.sh /usr/local/libexec/idleo-import-images
COPY idleo-images.service /etc/systemd/system/idleo-images.service
RUN chmod 0755 /usr/local/libexec/idleo-import-images \
    && mkdir -p /etc/systemd/system/multi-user.target.wants \
    && ln -s /etc/systemd/system/idleo-images.service /etc/systemd/system/multi-user.target.wants/idleo-images.service

RUN printf '%s\n' "${IDLEO_DISTRO_VERSION}" > /etc/idleo-distro-version

# Nothing is started by the image itself: systemd (wsl.conf's boot setting) starts containerd, and the host's app the rest.
CMD ["/bin/bash"]
