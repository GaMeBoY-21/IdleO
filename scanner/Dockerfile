# The image scanner's container (ADR 0042): Trivy, pinned by digest, and our idleo-scanner beside it. It runs as a user of
# its own (10001), on a read-only root, with every capability dropped; its only writable places are its scratch disk
# (/scratch, a volume of its own, which bounds what a check can download) and Trivy's database cache (/cache). It holds no
# database address, no storage keys and no secret but its own token for the control plane's internal API.
#
#   docker build -t idleo-scanner scanner/

# The builder: the toolchain rust-toolchain.toml pins, on Alpine for the same libc as Trivy's image.
FROM rust:1.99.0-alpine3.24@sha256:0cce0a5e0e8ba67b455257a3a02a1d99005f382748789d6464460028810f1627 AS build
RUN apk add --no-cache musl-dev
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml ./
COPY src src
RUN cargo build --release --locked --bin idleo-scanner

# Trivy 0.75.0 (aquasec/trivy:0.75.0), its multi-platform index.
FROM aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa
RUN addgroup -g 10001 scanner && adduser -D -H -u 10001 -G scanner -s /sbin/nologin scanner \
    && mkdir -p /scratch /cache/trivy && chown 10001:10001 /scratch /cache/trivy
COPY --from=build /src/target/release/idleo-scanner /usr/local/bin/idleo-scanner
USER 10001:10001
ENV SCANNER_SCRATCH=/scratch SCANNER_TRIVY=/usr/local/bin/trivy SCANNER_TRIVY_CACHE=/cache/trivy HOME=/scratch
VOLUME ["/scratch", "/cache"]
ENTRYPOINT ["/usr/local/bin/idleo-scanner"]
CMD ["work"]
