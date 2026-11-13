/**
 * One component for each pattern (web/DESIGN.md "Components", ADR 0059): on every site, a screen that builds its own
 * version of a shared pattern fails lint, so the sites can't drift back into several ways of doing one thing. Each is
 * its component's alone:
 * - the page's one h1: `PageHeader` or `PageTitle` (components/ui/Headers.tsx);
 * - a table: `DataTable` (components/ui/DataTable.tsx; the console's dense one is built on it, and the control room's
 *   heat maps, a chart, are features/admin/HeatGrid.tsx);
 * - labels and values: `KeyValueList`, `StatGrid` and `StatTile` (components/ui/KeyValues.tsx);
 * - a dialog: `Sheet` or `ConfirmDialog` (components/ui/Sheet.tsx);
 * - a spinner: a pending `Button` (components/ui/Button.tsx);
 * - a state's chip: `StatusChip` (components/ui/StatusChip.tsx), from the one map in lib/states.ts.
 * The website (src/site/) is a build of its own, without the app's code, and keeps its own.
 */
const OWNERS = {
  h1: /components\/ui\/Headers\.tsx$/,
  table: /components\/ui\/DataTable\.tsx$|features\/admin\/(Table|HeatGrid)\.tsx$/,
  dl: /components\/ui\/KeyValues\.tsx$/,
  dialog: /components\/ui\/Sheet\.tsx$/,
  spinner: /components\/ui\/Button\.tsx$/,
  chip: /components\/ui\/StatusChip\.tsx$/,
};
const ELEMENTS = { h1: "h1", table: "table", dl: "dl", dialog: "dialog" };
// A padded pill painted in a status colour is a state chip (a bare dot, `size-1.5 rounded-full bg-success`, is only a mark).
const CHIP =
  /\b(?:px|py)-\d[^"'`]*\brounded-full[^"'`]*\bbg-(?:success|warning|danger|info)\b|\brounded-full[^"'`]*\b(?:px|py)-\d[^"'`]*\bbg-(?:success|warning|danger|info)\b|\bbg-(?:success|warning|danger|info)\b[^"'`]*\b(?:px|py)-\d[^"'`]*\brounded-full/;

export default {
  meta: {
    type: "problem",
    docs: { description: "A screen uses the shared component for each pattern." },
    messages: {
      h1: "A page's title is PageHeader or PageTitle (components/ui/Headers.tsx), never its own h1.",
      table: "A table is DataTable (components/ui/DataTable.tsx).",
      dl: "Labels and values are KeyValueList, StatGrid or StatTile (components/ui/KeyValues.tsx).",
      dialog: "A dialog is Sheet or ConfirmDialog (components/ui/Sheet.tsx).",
      spinner: "A spinner is a pending Button's (busy); a screen's loading is a skeleton.",
      chip: "A state is a StatusChip, from the one map in lib/states.ts.",
    },
    schema: [],
  },
  create(context) {
    const file = context.filename.replaceAll("\\", "/");
    if (!/\/src\//.test(file) || /\/src\/(site|gallery)\//.test(file) || /\.test\.tsx?$/.test(file)) {
      return {};
    }
    const owns = (pattern) => OWNERS[pattern].test(file);
    const strings = (node, text) => {
      if (/\banimate-spin\b/.test(text) && !owns("spinner")) {
        context.report({ node, messageId: "spinner" });
      }
      if (CHIP.test(text) && !owns("chip")) {
        context.report({ node, messageId: "chip" });
      }
    };
    return {
      ImportDeclaration(node) {
        const names = node.specifiers.map((specifier) => specifier.imported?.name ?? specifier.local.name);
        if (
          node.source.value === "radix-ui" &&
          names.some((name) => name === "Dialog" || name === "AlertDialog") &&
          !owns("dialog")
        ) {
          context.report({ node, messageId: "dialog" });
        }
      },
      JSXOpeningElement(node) {
        if (node.name.type === "JSXIdentifier") {
          for (const [pattern, element] of Object.entries(ELEMENTS)) {
            if (node.name.name === element && !owns(pattern)) {
              context.report({ node, messageId: pattern });
            }
          }
        }
        for (const attribute of node.attributes) {
          if (
            attribute.type === "JSXAttribute" &&
            attribute.name.name === "role" &&
            attribute.value?.type === "Literal"
          ) {
            if (attribute.value.value === "table" && !owns("table")) {
              context.report({ node, messageId: "table" });
            }
            if (
              (attribute.value.value === "dialog" || attribute.value.value === "alertdialog") &&
              !owns("dialog")
            ) {
              context.report({ node, messageId: "dialog" });
            }
          }
        }
      },
      Literal(node) {
        if (typeof node.value === "string") {
          strings(node, node.value);
        }
      },
      TemplateElement(node) {
        strings(node, node.value.raw);
      },
    };
  },
};
