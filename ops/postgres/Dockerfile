# The PostgreSQL we run, in development and production (ADR 0036): the same image the tests use, pinned by digest,
# with WAL-G pinned by version and checksum, archiving every WAL segment and taking full backups to S3-compatible
# storage (R2 in production, MinIO locally), encrypted with the backup key's public half before anything leaves the
# server. docs/runbooks/backups.md.
FROM postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4 AS fetch
ARG TARGETARCH
# github.com/wal-g/wal-g/releases/tag/v3.0.9, the PostgreSQL build for Ubuntu 24.04 (glibc 2.39; this image's is newer).
ADD --checksum=sha256:8015836f246a978f27b31a2462eac7765312558ae5b11ac033322b555abf9e22 \
    https://github.com/wal-g/wal-g/releases/download/v3.0.9/wal-g-pg-24.04-amd64 /fetch/wal-g-amd64
ADD --checksum=sha256:1edd72c3387bfc3a1c19acad9daa36709dbb7cff0642f7044e175d5f8b555d64 \
    https://github.com/wal-g/wal-g/releases/download/v3.0.9/wal-g-pg-24.04-aarch64 /fetch/wal-g-arm64
RUN install -m 0755 "/fetch/wal-g-${TARGETARCH}" /usr/local/bin/wal-g

FROM postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.title="idleo-postgres" \
      org.opencontainers.image.description="PostgreSQL with WAL-G, as IdleO runs it" \
      org.opencontainers.image.source="https://github.com/GaMeBoY-21/IdleO" \
      org.opencontainers.image.licenses="LicenseRef-IdleO-All-Rights-Reserved" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
COPY --from=fetch /usr/local/bin/wal-g /usr/local/bin/wal-g
COPY archive-wal backup-full idleo-postgres /usr/local/bin/
# A new volume's first start makes the migration and application users when their passwords are set (ADR 0045).
COPY initdb/ /docker-entrypoint-initdb.d/
HEALTHCHECK --interval=10s --timeout=5s CMD ["pg_isready", "-q", "-h", "/var/run/postgresql"]
ENTRYPOINT ["idleo-postgres"]
CMD ["postgres"]
