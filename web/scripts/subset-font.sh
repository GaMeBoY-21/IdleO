#!/usr/bin/env bash
# Cuts the app's one font file from Inter 4.1's variable font (web/DESIGN.md "Typography"): Basic Latin,
# Latin-1, the punctuation, arrows, minus and almost-equal signs we use, and the rupee sign; tabular figures
# kept; weight 400 to 700; optical size pinned at text size. Needs Python's fonttools and brotli
# (pip install fonttools brotli). Run it only to change the subset; the output is committed.
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
(cd "$work" && npm pack inter-ui@4.1.1 --silent >/dev/null && tar xzf inter-ui-4.1.1.tgz)
python3 -m fontTools.varLib.instancer "$work/package/variable/InterVariable.woff2" opsz=14 wght=400:700 \
  -o "$work/inter.ttf" -q
python3 -m fontTools.subset "$work/inter.ttf" \
  --unicodes="U+0020-007E,U+00A0-00FF,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248" \
  --layout-features="kern,liga,calt,tnum,case,ccmp,locl,mark,mkmk" \
  --flavor=woff2 --output-file=public/fonts/inter-4.1-subset.woff2
cp "$work/package/LICENSE.txt" public/fonts/LICENSE-Inter.txt
ls -l public/fonts/inter-4.1-subset.woff2
