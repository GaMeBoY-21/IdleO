"""Draws the IdleO wordmark's letters (web/DESIGN.md "Brand") as SVG path data, from Inter 4.1 SemiBold, so the
wordmark needs no font. Prints the path and the advance width in font units. Needs fonttools and brotli; run it
only when the wordmark changes, and paste the output into src/brand/Wordmark.tsx.

    python3 scripts/wordmark.py /path/to/InterVariable.woff2
"""
import sys
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

font = instantiateVariableFont(TTFont(sys.argv[1]), {"wght": 600, "opsz": 32})
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()
ascender = font["hhea"].ascent
pen = SVGPathPen(glyphs)
x = 0
for char in "Idle":
    name = cmap[ord(char)]
    # Font units are y-up; SVG is y-down, with the top at the ascender.
    glyphs[name].draw(TransformPen(pen, (1, 0, 0, -1, x, ascender)))
    x += glyphs[name].width
print(f"advance={x} ascender={ascender} cap={font['OS/2'].sCapHeight} unitsPerEm={font['head'].unitsPerEm}")
print(pen.getCommands())
