# The sealed Linux image the Windows installer will import into WSL2 (docs/plan/host-experience.md §2, ADR 0032's round):
# our own distribution, built here, never the Store's and never downloaded inside at install time. Minimal Ubuntu 24.04,
# containerd, nerdctl and the CNI plugins it needs, the NVIDIA container toolkit and nftables at pinned versions, the
# lockdown (wsl.conf), the sandbox's network rules, idleo-runner (ADR 0035), the real job runner, and an unprivileged
# `idleo` user. No SSH server, no compiler, no sudo, no Docker.
#
# Built for linux/amd64 (scripts/build-distro.sh): the laptops are x86-64 whatever machine builds this. The development
# stand-in on an Apple Silicon Mac (scripts/dev-distro.sh) is the same recipe for linux/arm64 (build-distro.sh --dev): amd64
# under Rosetta can't run a container inside itself (runc's seccomp filter and read-only mounts need syscalls Rosetta doesn't
# translate), so the sandbox could not be tested there at all. Only the architecture's own downloads differ.
# The base is pinned by digest, the packages by version, and the NVIDIA repository's key by fingerprint, so a build
# that can't get exactly these fails instead of changing quietly. Every other Ubuntu package comes from the archive as it
# stood at one moment (snapshot.ubuntu.com, APT::Snapshot; arm64's ports.ubuntu.com is served from the same /ubuntu/
# snapshot, which apt doesn't know by itself), agent/distro/SNAPSHOT, which build-distro.sh passes in, so two
# builds a month apart install the same packages; scripts/bump-distro-snapshot.sh moves it forward, a deliberate rebuild
# for the month's security updates. To move a pin forward, change it, build, and run agent/distro/test.sh.

# ubuntu:24.04 (the multi-architecture index), 2026-09-30.

# Stage 1: idleo-runner, built from the repository's own source with the pinned Rust toolchain (rust:1.99.0-bookworm, the
# multi-architecture index, 2026-10-19; glibc 2.36, which the distribution's 2.39 runs). The build context has agent/ and
# proto/ and nothing else.
FROM rust:1.99.0-bookworm@sha256:114c7a4425406451c2866b6aafe69fe29b1b298832db1277d411ac73c82d04d6 AS runner
WORKDIR /src
COPY proto /src/proto
COPY agent /src/agent
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/src/agent/target \
    cd /src/agent && cargo build --release --locked -p runner && install -D target/release/idleo-runner /out/idleo-runner

# Just the runner, for scripts/dev-distro.sh refresh-runner (a quick loop while changing it): `--target runner-binary`.
FROM scratch AS runner-binary
COPY --from=runner /out/idleo-runner /idleo-runner

# Stage 2: nerdctl and the three CNI plugins a session network needs, each download checked against the SHA-256 written
# here before anything is unpacked. The tools that fetch them never reach the final image.
FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac AS fetch
# containerd's own client, and the containers' network plugins (github.com/containerd/nerdctl, containernetworking/plugins).
ARG TARGETARCH
# The Ubuntu archive's moment (agent/distro/SNAPSHOT), for this stage's own downloads too. The snapshot server is HTTPS
# only and the base has no CA bundle yet, so apt borrows the pinned builder image's for these downloads alone; what it
# fetches is checked against Ubuntu's signing key in the base, as always.
ARG UBUNTU_SNAPSHOT
COPY --from=runner /etc/ssl/certs/ca-certificates.crt /tmp/bootstrap-ca.crt
ARG NERDCTL_VERSION=2.4.1
ARG NERDCTL_SHA256_amd64=f6bbf02fd8a299cbcbc71a0bad113c1b63b3f33689bbb7d51b0fa98c6d361d5e
ARG NERDCTL_SHA256_arm64=2b60e7311589342abec39ffd2c172bef51da31ccc1278a89ec3aa04d1d3040bb
ARG CNI_VERSION=1.9.1
ARG CNI_SHA256_amd64=b98f74a0f8522f0a83867178729c1aa70f2158f90c45a2ca8fa791db1c76b303
ARG CNI_SHA256_arm64=56171987d3947707c3563db2f4001bccaf50fd63468611b9f3cbecb1375ee7ec
RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) nerdctl_sha="${NERDCTL_SHA256_amd64}"; cni_sha="${CNI_SHA256_amd64}" ;; \
      arm64) nerdctl_sha="${NERDCTL_SHA256_arm64}"; cni_sha="${CNI_SHA256_arm64}" ;; \
      *) echo "no pinned downloads for ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    test -n "${UBUNTU_SNAPSHOT}"; \
    printf '%s\n' "APT::Snapshot \"${UBUNTU_SNAPSHOT}\";" \
        'Acquire::Snapshots::URI::Host::ports.ubuntu.com "https://snapshot.ubuntu.com/ubuntu/@SNAPSHOTID@/";' \
        > /etc/apt/apt.conf.d/50idleo-snapshot; \
    echo 'Acquire::https::CAInfo "/tmp/bootstrap-ca.crt"; Acquire::Retries "10"; Acquire::Retries::Delay::Maximum "30"; APT::Update::Error-Mode "any";' > /etc/apt/apt.conf.d/49idleo-bootstrap-ca; \
    for try in 1 2 3 4 5; do apt-get update && break; [ "$try" -lt 5 ] && sleep 30; done; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    mkdir -p /out/cni /tmp/nerdctl /tmp/cni; \
    curl -fsSL -o /tmp/nerdctl.tgz "https://github.com/containerd/nerdctl/releases/download/v${NERDCTL_VERSION}/nerdctl-${NERDCTL_VERSION}-linux-${TARGETARCH}.tar.gz"; \
    echo "${nerdctl_sha}  /tmp/nerdctl.tgz" | sha256sum -c -; \
    tar -xzf /tmp/nerdctl.tgz -C /tmp/nerdctl nerdctl; \
    install -m 0755 /tmp/nerdctl/nerdctl /out/nerdctl; \
    curl -fsSL -o /tmp/cni.tgz "https://github.com/containernetworking/plugins/releases/download/v${CNI_VERSION}/cni-plugins-linux-${TARGETARCH}-v${CNI_VERSION}.tgz"; \
    echo "${cni_sha}  /tmp/cni.tgz" | sha256sum -c -; \
    tar -xzf /tmp/cni.tgz -C /tmp/cni ./bridge ./host-local ./loopback; \
    install -m 0755 /tmp/cni/bridge /tmp/cni/host-local /tmp/cni/loopback /out/cni/

FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac

ARG CONTAINERD_VERSION=2.2.1-0ubuntu1~24.04.3
ARG RUNC_VERSION=1.3.4-0ubuntu1~24.04.1
ARG SYSTEMD_VERSION=255.4-1ubuntu8.18
ARG NVIDIA_TOOLKIT_VERSION=1.20.1-1
ARG NFTABLES_VERSION=1.0.9-1ubuntu0.1
# For the encrypted scratch disk (ADR 0038): cryptsetup-bin opens a LUKS2 volume per session, and kmod lets the runner
# load the dm-crypt module (Microsoft's WSL kernel has it as a module, not built in).
ARG CRYPTSETUP_VERSION=2:2.7.0-1ubuntu4.2
ARG KMOD_VERSION=31+20240202-2ubuntu7.2
ARG LIBPOPT_VERSION=1.19+dfsg-1build1
# The fingerprint of NVIDIA's repository signing key (public): the build refuses any other.
ARG NVIDIA_REPOSITORY_SIGNER=C95B321B61E88C1809C4F759DDCAE044F796ECB0
ARG IDLEO_DISTRO_VERSION
# The Ubuntu archive's moment every Ubuntu package comes from (agent/distro/SNAPSHOT); kept in the image as
# /etc/idleo-distro-snapshot. NVIDIA's repository has no snapshots: its packages are pinned by version above.
ARG UBUNTU_SNAPSHOT

ENV DEBIAN_FRONTEND=noninteractive

# The snapshot server is HTTPS only and the base has no CA bundle until Ubuntu's ca-certificates is installed, so apt
# borrows the pinned builder image's for this layer; it's removed at the end of it, leaving only the snapshot's own.
COPY --from=runner /etc/ssl/certs/ca-certificates.crt /tmp/bootstrap-ca.crt

# The tools used to add NVIDIA's repository are removed again in the same layer.
RUN set -eux; \
    test -n "${UBUNTU_SNAPSHOT}"; \
    printf '%s\n' "APT::Snapshot \"${UBUNTU_SNAPSHOT}\";" \
        'Acquire::Snapshots::URI::Host::ports.ubuntu.com "https://snapshot.ubuntu.com/ubuntu/@SNAPSHOTID@/";' \
        > /etc/apt/apt.conf.d/50idleo-snapshot; \
    echo 'Acquire::https::CAInfo "/tmp/bootstrap-ca.crt"; Acquire::Retries "10"; Acquire::Retries::Delay::Maximum "30"; APT::Update::Error-Mode "any";' > /etc/apt/apt.conf.d/49idleo-bootstrap-ca; \
    printf '%s\n' "${UBUNTU_SNAPSHOT}" > /etc/idleo-distro-snapshot; \
    for try in 1 2 3 4 5; do apt-get update && break; [ "$try" -lt 5 ] && sleep 30; done; \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg; \
    curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey -o /tmp/nvidia.asc; \
    test "$(gpg --show-keys --with-colons /tmp/nvidia.asc | awk -F: '/^fpr/ {print $10; exit}')" = "${NVIDIA_REPOSITORY_SIGNER}"; \
    gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg /tmp/nvidia.asc; \
    echo "deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://nvidia.github.io/libnvidia-container/stable/deb/$(dpkg --print-architecture) /" \
        > /etc/apt/sources.list.d/nvidia-container-toolkit.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        "systemd=${SYSTEMD_VERSION}" "systemd-sysv=${SYSTEMD_VERSION}" \
        "containerd=${CONTAINERD_VERSION}" "runc=${RUNC_VERSION}" \
        "nvidia-container-toolkit=${NVIDIA_TOOLKIT_VERSION}" \
        "nvidia-container-toolkit-base=${NVIDIA_TOOLKIT_VERSION}" \
        "libnvidia-container-tools=${NVIDIA_TOOLKIT_VERSION}" \
        "libnvidia-container1=${NVIDIA_TOOLKIT_VERSION}" \
        "nftables=${NFTABLES_VERSION}" \
        "cryptsetup-bin=${CRYPTSETUP_VERSION}" "libpopt0=${LIBPOPT_VERSION}" "kmod=${KMOD_VERSION}"; \
    apt-get purge -y --auto-remove curl gnupg; \
    rm -f /etc/apt/apt.conf.d/49idleo-bootstrap-ca /tmp/bootstrap-ca.crt; \
    rm -rf /tmp/nvidia.asc /var/lib/apt/lists/* /var/cache/apt/* /var/log/apt /var/log/dpkg.log /var/log/alternatives.log \
        /usr/share/doc /usr/share/man /usr/share/info /usr/share/lintian /root/.gnupg

# The user jobs run as: a system account with no login shell, not in any group that grants anything.
RUN useradd --system --create-home --home-dir /var/lib/idleo --shell /usr/sbin/nologin --user-group idleo

# The lockdown, the same file Phase 0 proves on a real laptop; the proof script beside it, with what it sources.
COPY wsl.conf /etc/wsl.conf
COPY verify-lockdown.sh gpu-common.sh /usr/local/share/idleo/
RUN chmod 0755 /usr/local/share/idleo/verify-lockdown.sh

# The container tools, and the runner that drives them (ADR 0035): nerdctl and the CNI plugins from stage 2, idleo-runner from
# stage 1. The sandbox's rules are built into the runner and loaded by idleo-sandbox.service before idleo-runner starts.
COPY --from=fetch /out/nerdctl /usr/local/bin/nerdctl
COPY --from=fetch /out/cni/ /opt/cni/bin/
COPY --from=runner /out/idleo-runner /usr/local/bin/idleo-runner
COPY sandbox.nft /usr/local/share/idleo/sandbox.nft
# The abuse blocklist (ADR 0038) the runner starts from, with its signature and the release key that checks it: the runner uses a
# list only if its signature verifies. A newer one, signed and handed over by the agent, replaces it without a new distribution,
# and the runner keeps the last good copy in /var/lib/idleo/blocklist/.
COPY blocklist.json blocklist.json.sig release.pub /usr/local/share/idleo/
COPY idleo-sandbox.service idleo-runner.service /etc/systemd/system/
RUN mkdir -p /etc/systemd/system/multi-user.target.wants /etc/idleo /var/lib/idleo \
    && ln -s /etc/systemd/system/idleo-sandbox.service /etc/systemd/system/multi-user.target.wants/idleo-sandbox.service \
    && ln -s /etc/systemd/system/idleo-runner.service /etc/systemd/system/multi-user.target.wants/idleo-runner.service

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
