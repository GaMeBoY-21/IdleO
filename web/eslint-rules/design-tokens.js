import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
/**
 * No colours or sizes typed outside the design tokens (web/DESIGN.md): a string in a component may not hold a
 * hex, rgb(), hsl() or oklch() colour, a Tailwind arbitrary value such as `p-[13px]` or `bg-[#123]`, space off the
 * 4/8 px grid, a z-index or a duration as a number; nor may a `style` prop set spacing, type, a layer or a duration. The
 * tokens, the brand and Google's mark are the only files allowed to name colours (eslint.config.js).
 */
const COLOUR = /#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab|lab|lch)\(/i;
// A utility with a bracketed value: `w-[..]`, `md:bg-[..]`. Bracketed variants like `data-[state=open]:` are
// selectors, not values, so they're allowed.
const ARBITRARY_VALUE = /(?:^|[\s:])[a-z][\w-]*-\[[^\]]+\](?=$|[\s])/;

// Padding, margin and gaps on the 4/8 px grid (web/DESIGN.md "Shapes and space"): the scale's half steps (2, 6, 10, 12,
// 14, 18, 20 px and the rest) stay for heights and line heights, never for space between things.
const OFF_GRID =
  /(?:^|[\s:])-?(?:p|px|py|pt|pb|pl|pr|ps|pe|m|mx|my|mt|mb|ml|mr|ms|me|gap|gap-x|gap-y|space-x|space-y)-(?:0\.5|1\.25|1\.5|2\.5|3|3\.5|4\.5|5|5\.5|6\.5|7|7\.5|8\.5|9|11|15)(?=$|\s)/;
// A layer is a token (src/design/tokens.ts, `layers`), never a number: `z-sheet`, not `z-40`.
const RAW_LAYER = /(?:^|[\s:])-?z-\d+(?=$|\s)/;
// A duration is a token too: `duration-appear`, not `duration-200`.
const RAW_DURATION = /(?:^|[\s:])(?:duration|delay)-\d+(?=$|\s)/;

// Every step the spacing scale has (src/design/tokens.ts, as tokens.gen.css writes it): the scale is explicit, so a class
// naming any other step (`h-56` before there was a 56) makes no CSS at all and silently does nothing.
const STEPS = new Set(
  [
    ...readFileSync(fileURLToPath(new URL("../src/design/tokens.gen.css", import.meta.url)), "utf8").matchAll(
      /--spacing-([\w\\.]+):/g,
    ),
  ].map((match) => match[1].replaceAll("\\", "")),
);
const SIZED =
  /(?:^|[\s:])-?(?:h|w|min-h|min-w|max-h|max-w|size|p|px|py|pt|pb|pl|pr|ps|pe|m|mx|my|mt|mb|ml|mr|ms|me|gap|gap-x|gap-y|top|bottom|left|right|inset|inset-x|inset-y|space-x|space-y|translate-x|translate-y|basis)-(\d+(?:\.\d+)?)(?=$|\s)/g;
// A grid names its columns at every width (`grid-cols-1` below the breakpoint that sets more): without, its one column
// is as wide as its widest word, and a long name or email pushes the page sideways.
const bareGrid = (text) => {
  const classes = text.split(/\s+/);
  return (
    classes.includes("grid") && !classes.some((name) => /^(grid-cols-|cols-)/.test(name) || name === "grid-flow-col")
  );
};
const unknownStep = (text) => [...text.matchAll(SIZED)].some((match) => !STEPS.has(match[1]));

function check(context, node, text) {
  if (COLOUR.test(text)) {
    context.report({ node, messageId: "colour" });
  } else if (ARBITRARY_VALUE.test(text)) {
    context.report({ node, messageId: "arbitrary" });
  } else if (OFF_GRID.test(text)) {
    context.report({ node, messageId: "grid" });
  } else if (RAW_LAYER.test(text)) {
    context.report({ node, messageId: "layer" });
  } else if (RAW_DURATION.test(text)) {
    context.report({ node, messageId: "duration" });
  } else if (unknownStep(text)) {
    context.report({ node, messageId: "step" });
  } else if (bareGrid(text)) {
    context.report({ node, messageId: "columns" });
  }
}

/** A `style` prop's own sizes, layers and durations: in px, as a number of px, or in ms. */
const STYLED =
  /^(padding|margin|gap|rowGap|columnGap|fontSize|lineHeight|zIndex|transition|transitionDuration|animation|animationDuration)/;

export default {
  meta: {
    type: "problem",
    messages: {
      colour: "Use a colour token from src/design/tokens.ts, not a raw colour.",
      arbitrary: "Use a token from the design system, not a Tailwind arbitrary value.",
      grid: "Space between things is on the 4/8 px grid: p-1, p-2, p-4, p-6, p-8… (web/DESIGN.md).",
      layer: "Use a layer token (z-raised, z-sticky, z-sheet, z-toast…), not a z-index number.",
      duration: "Use a motion token (duration-press, duration-appear, duration-large), not a number of ms.",
      styled: "Spacing, type, layers and durations come from the design tokens, not a style prop.",
      columns: "A grid names its columns at every width: add grid-cols-1 (or a cols- utility) beside the breakpoints' own.",
      step: "That step isn't on the spacing scale (src/design/tokens.ts), so the class makes no CSS and does nothing.",
    },
    schema: [],
  },
  create(context) {
    return {
      Literal(node) {
        if (typeof node.value === "string") {
          check(context, node, node.value);
        }
      },
      TemplateElement(node) {
        check(context, node, node.value.raw);
      },
      Property(node) {
        const name = node.key?.name ?? node.key?.value;
        const inStyle =
          node.parent?.parent?.type === "JSXExpressionContainer" &&
          node.parent.parent.parent?.name?.name === "style";
        if (inStyle && typeof name === "string" && STYLED.test(name) && node.value.type === "Literal") {
          context.report({ node, messageId: "styled" });
        }
      },
    };
  },
};
