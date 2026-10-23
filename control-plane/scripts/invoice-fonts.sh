#!/usr/bin/env bash
# Makes the two font files invoices are rendered with (ADR 0039): Plus Jakarta Sans, the web app's one face, as static
# Regular (400) and Bold (700) TrueType instances, cut to the same characters as the web's subset (with the rupee
# sign), so the PDF embeds them and rendering never fetches anything. The source is the upstream repository at the
# same pinned commit as web/scripts/subset-font.sh (SIL Open Font License 1.1). Needs Python's fonttools. Run it only
# to change the fonts; the output is committed.
set -euo pipefail
cd "$(dirname "$0")/.."
commit=5854bde9039ce212dd34a8ab369aae66ef6e4b6b
base="https://raw.githubusercontent.com/tokotype/PlusJakartaSans/$commit"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
out=src/main/resources/fonts
mkdir -p "$out"
curl -fsSL -o "$work/source.ttf" "$base/fonts/variable/PlusJakartaSans%5Bwght%5D.ttf"
curl -fsSL -o "$work/OFL.txt" "$base/OFL.txt"
for weight in Regular:400 Bold:700; do
  name="${weight%%:*}"
  python3 -m fontTools.varLib.instancer "$work/source.ttf" "wght=${weight#*:}" --static -o "$work/$name.ttf" -q
  python3 -m fontTools.subset "$work/$name.ttf" \
    --unicodes="U+0020-007E,U+00A0-00FF,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248" \
    --layout-features="kern,liga,tnum" --output-file="$out/PlusJakartaSans-$name.ttf"
done
cp "$work/OFL.txt" "$out/LICENSE-PlusJakartaSans.txt"
ls -l "$out"
