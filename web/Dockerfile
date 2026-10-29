# check=skip=SecretsUsedInArgOrEnv
# The web image (ADR 0045): the app (web/dist) and the website (web/dist-website), built here and served by nginx as
# two server blocks, the app on 8080 and the website on 8081, with each build's headers, redirects and rewrites
# (scripts/nginx-config.ts, docs/runbooks/deployment.md). Production serves both from Cloudflare Pages; this image is
# the local cluster's, and production's fallback. It runs as nginx's own unprivileged user (101) on a read-only root,
# writing only to /tmp (a tmpfs in the chart).
#
#   docker build -f web/Dockerfile -t idleo-web .      (from the repository root: the build reads api/, templates/ and
#                                                        docs/site/ too)
#
# The builds are made with the addresses and settings they'll be used with (web/src/build-settings.ts refuses a
# wrong combination), passed as build arguments; their defaults are the local cluster's (scripts/k8s/up.sh).

FROM node:24.21.0-trixie-slim@sha256:173f125896c3b47ddf056734c7ea789d04595a6a08769a8f78e0df642781fb66 AS build
ARG IDLEO_DEPLOYMENT=local
ARG IDLEO_RELEASE=development
ARG IDLEO_SITE_URL=https://idleo.localhost:8443
ARG IDLEO_APP_URL=https://app.idleo.localhost:8443
ARG VITE_API_URL=https://api.idleo.localhost:8443
ARG VITE_STORAGE_URL=http://storage.idleo.localhost:19100
ARG VITE_AUTH=oidc-mock
ARG VITE_OIDC_ISSUER=http://signin.idleo.localhost:18099/idleo-local
ARG VITE_OIDC_CLIENT_ID=idleo-local
ARG VITE_CHECKOUT_URL=
ARG VITE_FIREBASE_API_KEY=
ARG VITE_FIREBASE_AUTH_DOMAIN=
ARG VITE_FIREBASE_PROJECT_ID=
ENV COREPACK_HOME=/corepack CI=true
WORKDIR /src/web
# pnpm as package.json pins it, through Node's corepack, which checks its signature.
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml web/.npmrc ./
RUN corepack enable
RUN --mount=type=cache,target=/pnpm-store pnpm install --frozen-lockfile --store-dir /pnpm-store
COPY api /src/api
COPY templates /src/templates
COPY docs/site /src/docs/site
COPY web ./
RUN pnpm build && pnpm build:site \
    && mkdir -p /out/conf \
    && node scripts/nginx-config.ts dist 8080 > /out/conf/app.conf \
    && node scripts/nginx-config.ts dist-website 8081 > /out/conf/website.conf

FROM nginxinc/nginx-unprivileged:1.31.7-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.title="idleo-web" \
      org.opencontainers.image.description="IdleO's web app and website, served by nginx" \
      org.opencontainers.image.source="https://github.com/GaMeBoY-21/IdleO" \
      org.opencontainers.image.licenses="LicenseRef-IdleO-All-Rights-Reserved" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
# The image's own example site goes, and curl, which nothing here uses; Alpine's packages as patched today, not as the
# base was built. Logs go to the container's output (the base links them to stdout and stderr).
USER root
RUN apk del --no-cache curl && apk upgrade --no-cache && rm -rf /etc/nginx/conf.d/default.conf /usr/share/nginx/html
USER 101
COPY --from=build /out/conf/ /etc/nginx/conf.d/
COPY --from=build /src/web/dist /usr/share/nginx/dist
COPY --from=build /src/web/dist-website /usr/share/nginx/dist-website
EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=3s CMD ["wget", "-q", "--spider", "http://127.0.0.1:8080/sign-in/"]
