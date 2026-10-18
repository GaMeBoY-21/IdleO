# The pinned toolchains on PATH, wherever this machine keeps them, for scripts/lib/stack.sh (so the end-to-end script
# and ./start.sh) and scripts/check-all.sh. A shell's own PATH may put another Java or Node first, and rustup or pnpm
# may be installed without being on it. The caller sets root (the repository).
# shellcheck shell=bash

# Java 25 (CLAUDE.md): Homebrew's on a Mac, Temurin's on Linux.
for dir in /opt/homebrew/opt/openjdk@25/bin /usr/lib/jvm/temurin-25-jdk-*/bin; do
  [[ -d "$dir" ]] && PATH="$dir:$PATH" && export JAVA_HOME="${dir%/bin}"
done
# Rust may be installed by rustup without being on PATH (Homebrew's rustup on a Mac); rust-toolchain.toml picks the
# version.
for dir in "$HOME/.cargo/bin" /opt/homebrew/opt/rustup/bin; do
  [[ -d "$dir" ]] && PATH="$PATH:$dir"
done
# LLVM's llvm-rc and lld, for building and linting the Windows app on a Mac (scripts/build-windows.sh).
for dir in /opt/homebrew/opt/llvm/bin /opt/homebrew/opt/lld/bin /opt/homebrew/opt/llvm@22/bin /opt/homebrew/opt/lld@22/bin; do
  [[ -d "$dir" ]] && PATH="$PATH:$dir"
done
# The web app pins Node 24 (web/.node-version); a Mac may keep it beside a newer Node.
[[ -d /opt/homebrew/opt/node@24/bin ]] && PATH="/opt/homebrew/opt/node@24/bin:$PATH"
# pnpm comes with Node, through corepack, at the version web/package.json pins; where it has no command yet, give it
# one in the gitignored private/ folder.
if ! command -v pnpm >/dev/null 2>&1 && command -v corepack >/dev/null 2>&1; then
  mkdir -p "$root/private/toolchain-bin"
  corepack enable --install-directory "$root/private/toolchain-bin" pnpm >/dev/null 2>&1 || true
  PATH="$root/private/toolchain-bin:$PATH"
fi
export PATH COREPACK_ENABLE_DOWNLOAD_PROMPT=0
