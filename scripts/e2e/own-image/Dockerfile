# The renter's own image the end-to-end test actually runs (scripts/e2e/real-runner.sh, scenario 7; ADR 0042): a real shell and
# a working directory of its own, built here and pushed to the development registry. The images scripts/e2e/make-image.py builds
# byte by byte have only the look of one (a fake /bin/sh) and are only ever checked, never run. It runs as root on purpose, so
# its check warns about that; the runner runs it as 10001 all the same.
FROM busybox@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e
WORKDIR /app
RUN echo "the renter's own image" > /app/about.txt
