/**
 * No colours or sizes typed outside the design tokens (web/DESIGN.md): a string in a component may not hold a
 * hex, rgb(), hsl() or oklch() colour, or a Tailwind arbitrary value such as `p-[13px]` or `bg-[#123]`. The
 * tokens, the brand and Google's mark are the only files allowed to name colours (eslint.config.js).
 */
const COLOUR = /#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab|lab|lch)\(/i;
// A utility with a bracketed value: `w-[..]`, `md:bg-[..]`. Bracketed variants like `data-[state=open]:` are
// selectors, not values, so they're allowed.
const ARBITRARY_VALUE = /(?:^|[\s:])[a-z][\w-]*-\[[^\]]+\](?=$|[\s])/;

function check(context, node, text) {
  if (COLOUR.test(text)) {
    context.report({ node, messageId: "colour" });
  } else if (ARBITRARY_VALUE.test(text)) {
    context.report({ node, messageId: "arbitrary" });
  }
}

export default {
  meta: {
    type: "problem",
    messages: {
      colour: "Use a colour token from src/design/tokens.ts, not a raw colour.",
      arbitrary: "Use a token from the design system, not a Tailwind arbitrary value.",
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
    };
  },
};
