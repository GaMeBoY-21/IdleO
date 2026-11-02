#!/usr/bin/env python3
"""Prints the wordmark's outline for src/brand/Wordmark.tsx: the name in Archivo at width 125% and weight 900, tracked -0.02em,
with the box the text had (its line height, 1.2). Needs fontTools and brotli, as scripts/subset-font.sh does."""
import pathlib
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

NAME, TRACKING, LINE_HEIGHT = "idleo", -0.02, 1.2
font = TTFont(pathlib.Path(__file__).parent.parent / "public/fonts/archivo-125-900-lower.woff2")
glyphs, cmap, em = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
ascent, descent = font["hhea"].ascent, font["hhea"].descent
top = -round(ascent + (LINE_HEIGHT * em - (ascent - descent)) / 2)
x, path = 0, ""
for letter in NAME:
    glyph = glyphs[cmap[ord(letter)]]
    pen = SVGPathPen(glyphs, ntos=lambda value: str(round(value)))
    glyph.draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))
    path += pen.getCommands()
    x += glyph.width + TRACKING * em
print(f'viewBox="0 {top} {round(x)} {round(LINE_HEIGHT * em)}" width="{x / em:g}em" height="{LINE_HEIGHT:g}em"')
print(path)
