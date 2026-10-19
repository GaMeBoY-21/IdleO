#!/usr/bin/env bash
# Prints a fingerprint of everything a development release of the Windows app is built from: the agent's and the
# protocol's files as they are on disk now (uncommitted changes and new files count), the build and signing scripts,
# the sealed Linux image's checksum, the setup program's addresses, and IDLEO_TEST_FAIL_TRIAL. publish.sh --dev notes
# it with the version it published, and ./start.sh --lan publishes again when it has changed, so a laptop is never
# offered an app older than the code on this Mac.
set -euo pipefail
cd "$(dirname "$0")/../.."
distro_version="$(tr -d '[:space:]' <agent/distro/VERSION)"
{
  git ls-files -z --cached --others --exclude-standard -- agent proto scripts/build-windows.sh \
    scripts/release/sign-windows.sh | xargs -0 shasum -a 256 2>/dev/null || true
  cat "dist/distro/idleo-distro-$distro_version.tar.gz.sha256" private/release-dev/setup.env 2>/dev/null || true
  printf 'fail-trial=%s\n' "${IDLEO_TEST_FAIL_TRIAL:-}"
} | shasum -a 256 | cut -d' ' -f1
