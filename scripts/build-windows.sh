#!/usr/bin/env bash
# Builds the Windows app's five programs on this Mac (ADR 0033), in release mode, with cargo-xwin for
# x86_64-pc-windows-msvc (the target the Windows CI uses), into dist/windows/, and prints their sizes.
#
#   scripts/build-windows.sh                 a development build: the development release key, and the setup program
#                                            pointing where IDLEO_SETUP_* say (the local stack without them)
#   scripts/build-windows.sh --release-keys  a release build: agent/release-keys/release.pub, and every IDLEO_SETUP_*
#                                            address must be set
#
# Needs: rustup's x86_64-pc-windows-msvc target, cargo-xwin (cargo install --locked cargo-xwin) and LLVM (Homebrew's
# llvm and lld: llvm-rc compiles the programs' icons and manifests). The MSVC CRT and Windows SDK cargo-xwin downloads
# are accepted under Microsoft's licence through XWIN_ACCEPT_LICENSE.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"
for dir in /opt/homebrew/opt/llvm/bin /opt/homebrew/opt/lld/bin /opt/homebrew/opt/llvm@22/bin /opt/homebrew/opt/lld@22/bin; do
  [[ -d "$dir" ]] && PATH="$dir:$PATH"
done
export PATH XWIN_ACCEPT_LICENSE=1
target=x86_64-pc-windows-msvc
features=()
if [[ "${1:-}" == --release-keys ]]; then
  features=(--features "setup/prod-keys,release/prod-keys")
fi
command -v cargo-xwin >/dev/null || { echo "build-windows: cargo-xwin isn't installed: cargo install --locked cargo-xwin" >&2; exit 1; }
command -v llvm-rc >/dev/null || { echo "build-windows: llvm-rc isn't on PATH: brew install llvm lld" >&2; exit 1; }
rustup target add --toolchain "$(sed -n 's/^channel = "\(.*\)"/\1/p' agent/rust-toolchain.toml)" "$target" >/dev/null 2>&1 || true

started=$SECONDS
(cd agent && cargo xwin build --release --locked --target "$target" -p agent -p tray -p launcher -p setup ${features[@]+"${features[@]}"})

out="$root/dist/windows"
rm -rf "$out"
mkdir -p "$out"
for program in idleo-agent idleo-hostd idleo-tray idleo-launcher IdleO-Setup; do
  cp "agent/target/$target/release/$program.exe" "$out/"
done
cp agent/setup/assets/fonts/LICENSE-PlusJakartaSans.txt "$out/"
echo "build-windows: $target, $((SECONDS - started)) s"
for program in idleo-agent idleo-hostd idleo-tray idleo-launcher IdleO-Setup; do
  bytes=$(wc -c <"$out/$program.exe" | tr -d ' ')
  printf '  %-20s %6.1f MB\n' "$program.exe" "$(echo "$bytes / 1000000" | bc -l)"
done
setup_bytes=$(wc -c <"$out/IdleO-Setup.exe" | tr -d ' ')
if ((setup_bytes >= 15 * 1000 * 1000)); then
  echo "build-windows: IdleO-Setup.exe is $((setup_bytes / 1000000)) MB, over the 15 MB the download should be" >&2
  exit 1
fi
