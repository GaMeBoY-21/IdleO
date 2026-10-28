#!/usr/bin/env bash
# Cuts the app's font files (web/DESIGN.md "Typography"): Latin (Basic Latin, Latin-1), the punctuation, arrows,
# minus and almost-equal signs we use, and the rupee sign; tabular figures kept.
#   - Archivo, two static instances of the variable font: width 125% at weight 900 (the headlines) and width 112% at
#     weight 800 (the titles inside panels). Never the whole variable font.
#   - Inter, one variable file for weights 400 to 700 at its 14 px optical size, and its accented letters as a second.
#   - JetBrains Mono, weight 400, for logs.
# The sources are the google/fonts repository at a pinned commit (SIL Open Font License 1.1, copied beside each file).
# Needs Python's fonttools and brotli (pip install fonttools brotli). Run it only to change the subset; the output is
# committed.
set -euo pipefail
cd "$(dirname "$0")/.."
commit=a4c0b21f839d7ac65a7d8b49eb81bddd110774a3
base="https://raw.githubusercontent.com/google/fonts/$commit/ofl"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# The first screen's file holds Basic Latin and the few signs we use; Inter's accented letters are a second file the
# browser fetches only for a page that has one (unicode-range in src/design/tokens.ts keeps the two in step).
basic="U+0020-007E,U+00A0,U+00A9,U+00B7,U+00D7,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248"
accents="U+00A1-00A8,U+00AA-00B6,U+00B8-00D6,U+00D8-00FF"
# Archivo at 125% / 900 is set uppercase: its lowercase is only the wordmark's, a file of its own.
caps="U+0020-0060,U+007B-007E,U+00A0,U+00A9,U+00B7,U+00D7,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248"
lower="U+0061-007A"
unicodes="$basic,$accents"
# No ligatures or contextual alternates: they cost 2.7 KB of the first screen's budget and change nothing we draw.
features="kern,tnum,case,ccmp,locl,mark,mkmk"
mkdir -p public/fonts

fetch() { curl -fsSL -o "$work/$2" "$base/$1/$3"; }
cut() { # source, axes, output, characters, layout features
  python3 -m fontTools.varLib.instancer "$work/$1" $2 -o "$work/instance.ttf" -q
  python3 -m fontTools.subset "$work/instance.ttf" --unicodes="${4:-$basic}" --layout-features="${5:-$features}" \
    --flavor=woff2 --output-file="public/fonts/$3"
}

fetch archivo archivo.ttf 'Archivo%5Bwdth,wght%5D.ttf'
fetch archivo OFL-Archivo.txt OFL.txt
fetch inter inter.ttf 'Inter%5Bopsz,wght%5D.ttf'
fetch inter OFL-Inter.txt OFL.txt
fetch jetbrainsmono jbmono.ttf 'JetBrainsMono%5Bwght%5D.ttf'
fetch jetbrainsmono OFL-JetBrainsMono.txt OFL.txt

cut archivo.ttf "wdth=125 wght=900" archivo-125-900.woff2 "$caps"
cut archivo.ttf "wdth=125 wght=900" archivo-125-900-lower.woff2 "$lower"
cut archivo.ttf "wdth=112 wght=800" archivo-112-800.woff2
cut inter.ttf "opsz=14 wght=400:700" inter-400-700.woff2
cut inter.ttf "opsz=14 wght=400:700" inter-400-700-latin1.woff2 "$accents"
cut jbmono.ttf "wght=400" jetbrains-mono-400.woff2 "$unicodes" "$features,liga,calt" # the logs keep the font's own ligatures

cp "$work/OFL-Archivo.txt" public/fonts/LICENSE-Archivo.txt
cp "$work/OFL-Inter.txt" public/fonts/LICENSE-Inter.txt
cp "$work/OFL-JetBrainsMono.txt" public/fonts/LICENSE-JetBrainsMono.txt
ls -l public/fonts
