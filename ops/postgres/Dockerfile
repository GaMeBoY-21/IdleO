# The PostgreSQL of ./start.sh's stack and the tests (ADR 0036): the same image the tests use, pinned by digest, with
# WAL-G pinned by version and checksum (fetch-wal-g.sh, wal-g.sha256), archiving every WAL segment and taking full backups to the stack's MinIO,
# encrypted with the development backup key's public half. The cluster's database (staging and production) is
# CloudNativePG's own image, with its backups (ADR 0047). docs/runbooks/backups.md.
FROM postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4 AS fetch
ARG TARGETARCH
# github.com/wal-g/wal-g/releases/tag/v3.0.9, the PostgreSQL build for Ubuntu 24.04 (glibc 2.39; this image's is newer).
# fetch-wal-g.sh downloads it, with retries and a cache, before any build (compose and the scripts run it); its hash is
# checked again here, so what goes in the image is the pinned file whoever put it there.
COPY wal-g.sha256 /fetch/wal-g.sha256
COPY wal-g/ /fetch/
RUN cd /fetch \
    && grep " wal-g-${TARGETARCH}$" wal-g.sha256 | sha256sum --check --strict \
    && install -m 0755 "wal-g-${TARGETARCH}" /usr/local/bin/wal-g

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
