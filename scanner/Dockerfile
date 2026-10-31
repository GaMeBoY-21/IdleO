# The image scanner's container (ADR 0042): Trivy, pinned by digest, and our idleo-scanner beside it. It runs as a user of
# its own (10001), on a read-only root, with every capability dropped; its only writable places are its scratch disk
# (/scratch, a volume of its own, which bounds what a check can download) and Trivy's database cache (/cache). It holds no
# database address, no storage keys and no secret but its own token for the control plane's internal API.
#
#   docker build -t idleo-scanner scanner/
#
# It serves nothing, so there's no HEALTHCHECK: a scanner that stops taking checks shows as checks the control plane
# times out (ADR 0042), which is what its alert watches.

# The builder: the toolchain rust-toolchain.toml pins, on Alpine for the same libc as Trivy's image.
FROM rust:1.99.0-alpine3.24@sha256:0cce0a5e0e8ba67b455257a3a02a1d99005f382748789d6464460028810f1627 AS build
RUN apk add --no-cache musl-dev
WORKDIR /src
COPY Cargo.toml Cargo.lock rust-toolchain.toml ./
COPY src src
RUN cargo build --release --locked --bin idleo-scanner

# Trivy 0.76.1 built from its release tag's own commit, as its release builds it, but with go-jose raised to 4.1.6
# (CVE-2026-116587, HIGH: a crafted JWK set exhausts the parser), which no Trivy release carries yet: nothing found in
# our own images is ever accepted (ADR 0048). The base below still supplies everything else. When a Trivy release has
# the fix, drop this stage and take that release's image.
FROM golang:1.27.3-alpine3.24@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS trivy
RUN apk add --no-cache git
WORKDIR /src
RUN git init -q && git remote add origin https://github.com/aquasecurity/trivy.git \
    && git fetch -q --depth 1 origin tag v0.76.1 \
    && test "$(git rev-parse 'v0.76.1^{commit}')" = e713f67f7d54decdcd9b3132c9751833dcced698 && git checkout -q v0.76.1
# The raise is committed on top of the tag, at a fixed time, so Go stamps the binary with what it is (a pseudo-version
# just after v0.76.1, which is how Trivy's own scan of it knows its fixes are in) and the build is the same every time.
RUN go get github.com/go-jose/go-jose/v4@v4.1.6 \
    && git add go.mod go.sum && GIT_AUTHOR_DATE=2026-10-31T00:00:00Z GIT_COMMITTER_DATE=2026-10-31T00:00:00Z \
      git -c user.name=idleo -c user.email=idleo@users.noreply.github.com commit -q -m "raise go-jose to 4.1.6" \
    && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X github.com/aquasecurity/trivy/pkg/version/app.ver=0.76.1" \
      -o /trivy ./cmd/trivy && go version -m /trivy | grep -E 'mod.*aquasecurity/trivy|go-jose/v4'

# Trivy 0.76.1 (aquasec/trivy:0.76.1), its multi-platform index.
FROM aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.title="idleo-scanner" \
      org.opencontainers.image.description="IdleO's checker for renters' own images" \
      org.opencontainers.image.source="https://github.com/GaMeBoY-21/IdleO" \
      org.opencontainers.image.licenses="LicenseRef-IdleO-All-Rights-Reserved" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
# Alpine's packages as patched today, not as the base was built.
RUN apk upgrade --no-cache && addgroup -g 10001 scanner && adduser -D -H -u 10001 -G scanner -s /sbin/nologin scanner \
    && mkdir -p /scratch /cache/trivy && chown 10001:10001 /scratch /cache/trivy
COPY --from=trivy /trivy /usr/local/bin/trivy
COPY --from=build /src/target/release/idleo-scanner /usr/local/bin/idleo-scanner
USER 10001:10001
ENV SCANNER_SCRATCH=/scratch SCANNER_TRIVY=/usr/local/bin/trivy SCANNER_TRIVY_CACHE=/cache/trivy HOME=/scratch
VOLUME ["/scratch", "/cache"]
ENTRYPOINT ["/usr/local/bin/idleo-scanner"]
CMD ["work"]
