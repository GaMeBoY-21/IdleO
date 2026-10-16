#!/usr/bin/env bash
# Cuts the app's one font file from Plus Jakarta Sans's variable font (web/DESIGN.md "Typography"): Basic Latin,
# Latin-1, the punctuation, arrows, minus and almost-equal signs we use, and the rupee sign; tabular figures
# kept; weight 400 to 800. The source is the upstream repository at a pinned commit (SIL Open Font License 1.1).
# Needs Python's fonttools and brotli (pip install fonttools brotli). Run it only to change the subset; the
# output is committed.
set -euo pipefail
cd "$(dirname "$0")/.."
commit=5854bde9039ce212dd34a8ab369aae66ef6e4b6b
base="https://raw.githubusercontent.com/tokotype/PlusJakartaSans/$commit"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "$work/source.ttf" "$base/fonts/variable/PlusJakartaSans%5Bwght%5D.ttf"
curl -fsSL -o "$work/OFL.txt" "$base/OFL.txt"
python3 -m fontTools.varLib.instancer "$work/source.ttf" wght=400:800 -o "$work/pjs.ttf" -q
python3 -m fontTools.subset "$work/pjs.ttf" \
  --unicodes="U+0020-007E,U+00A0-00FF,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248" \
  --layout-features="kern,liga,calt,tnum,case,ccmp,locl,mark,mkmk" \
  --flavor=woff2 --output-file=public/fonts/plus-jakarta-sans-subset.woff2
cp "$work/OFL.txt" public/fonts/LICENSE-PlusJakartaSans.txt
ls -l public/fonts/plus-jakarta-sans-subset.woff2
