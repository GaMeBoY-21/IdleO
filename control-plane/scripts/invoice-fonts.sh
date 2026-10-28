#!/usr/bin/env bash
# Makes the font files invoices and statements are rendered with (ADR 0039, ADR 0043): the web app's own faces, as
# static TrueType files, cut from the web app's subsets (web/public/fonts, which web/scripts/subset-font.sh cuts from a
# pinned commit of google/fonts; SIL Open Font License 1.1), so a PDF and the screen share one source and rendering never
# fetches anything.
#   - Inter Regular (400) and Bold (700): the variable subset instanced at each weight, its accented letters merged in;
#   - Archivo Display: width 125% at weight 900, the uppercase and the lowercase files merged, for the wordmark and the
#     document's title.
# Needs Python's fonttools and brotli (pip install fonttools brotli). Run it only to change the fonts; the output is
# committed.
set -euo pipefail
cd "$(dirname "$0")/.."
web=../web/public/fonts
out=src/main/resources/fonts
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"
python3 - "$web" "$work" "$out" <<'PY'
import sys
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

web, work, out = sys.argv[1:4]

def load(name):
    font = TTFont(f"{web}/{name}")
    font.flavor = None
    return font

def named(font, family, style, weight):
    """Gives the merged file its own name (the instancer leaves the variable font's), and its weight."""
    full = f"{family} {style}"
    postscript = f"{family}-{style}".replace(" ", "")
    for record in list(font["name"].names):
        if record.nameID in (1, 2, 3, 4, 6, 16, 17, 21, 22, 25):
            font["name"].removeNames(nameID=record.nameID)
    for name_id, value in ((1, family), (2, "Regular"), (3, postscript), (4, full), (6, postscript)):
        font["name"].setName(value, name_id, 3, 1, 0x409)
    font["OS/2"].usWeightClass = weight
    return font

for name, weight in (("Regular", 400), ("Bold", 700)):
    for part, file in (("a", "inter-400-700.woff2"), ("b", "inter-400-700-latin1.woff2")):
        instancer.instantiateVariableFont(load(file), {"wght": weight}).save(f"{work}/{part}-{name}.ttf")
    merged = Merger().merge([f"{work}/a-{name}.ttf", f"{work}/b-{name}.ttf"])
    named(merged, "Inter", name, weight).save(f"{out}/Inter-{name}.ttf")

display = Merger().merge([f"{web}/archivo-125-900.woff2", f"{web}/archivo-125-900-lower.woff2"])
named(display, "Archivo Display", "Black", 900).save(f"{out}/Archivo-Display.ttf")
PY
cp "$web/LICENSE-Inter.txt" "$web/LICENSE-Archivo.txt" "$out/"
ls -l "$out"
