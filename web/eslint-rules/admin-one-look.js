/**
 * One look, one behaviour (ADR 0057): in the admin console, a dialog is the one `Sheet`, a confirm is the one `ActionSheet`,
 * empty, loading and error states are `ui.tsx`'s, and a problem is shown by `Problem`. A screen that builds its own fails
 * lint, so the console can't drift back into several ways of doing the same thing.
 */
const OWNERS = {
  dialog: /features\/admin\/Sheet\.tsx$/,
  states: /features\/admin\/ui\.tsx$/,
};

export default {
  meta: {
    type: "problem",
    docs: { description: "An admin screen uses the console's one component for each pattern." },
    messages: {
      ownDialog: "Use the console's Sheet or ActionSheet: only Sheet.tsx opens a dialog.",
      browserDialog: "No browser dialogs in the console: use ActionSheet.",
      ownState: "Use QueryView, PagedView, Empty or Problem from ui.tsx for this state.",
      ownProblem: "Use <Problem error={...} /> to show what went wrong.",
    },
    schema: [],
  },
  create(context) {
    const file = context.filename.replaceAll("\\", "/");
    if (!file.includes("features/admin/") || /\.test\.tsx?$/.test(file)) {
      return {};
    }
    return {
      ImportDeclaration(node) {
        const from = node.source.value;
        const names = node.specifiers.map((specifier) => specifier.imported?.name ?? specifier.local.name);
        if (
          !OWNERS.dialog.test(file) &&
          ((from === "radix-ui" && names.some((name) => name === "Dialog" || name === "AlertDialog")) ||
            from.startsWith("@radix-ui/react-dialog") ||
            from.startsWith("@radix-ui/react-alert-dialog"))
        ) {
          context.report({ node, messageId: "ownDialog" });
        }
        if (
          !OWNERS.states.test(file) &&
          from === "@/components/ui/States" &&
          names.some((name) => name === "EmptyState" || name === "ErrorState" || name === "StaleNotice")
        ) {
          context.report({ node, messageId: "ownState" });
        }
      },
      CallExpression(node) {
        const callee = node.callee;
        const isBrowserDialog = (name) => ["alert", "confirm", "prompt"].includes(name);
        const ownFunction = (name) => {
          for (let scope = context.sourceCode.getScope(node); scope; scope = scope.upper) {
            if (scope.set.get(name)?.defs.length) {
              return true;
            }
          }
          return false;
        };
        if (
          (callee.type === "Identifier" && isBrowserDialog(callee.name) && !ownFunction(callee.name)) ||
          (callee.type === "MemberExpression" &&
            callee.object.type === "Identifier" &&
            callee.object.name === "window" &&
            callee.property.type === "Identifier" &&
            isBrowserDialog(callee.property.name))
        ) {
          context.report({ node, messageId: "browserDialog" });
        }
      },
      JSXOpeningElement(node) {
        if (node.name.type === "JSXIdentifier" && node.name.name === "dialog" && !OWNERS.dialog.test(file)) {
          context.report({ node, messageId: "ownDialog" });
        }
        for (const attribute of node.attributes) {
          if (
            attribute.type === "JSXAttribute" &&
            attribute.name.name === "role" &&
            attribute.value?.type === "Literal" &&
            (attribute.value.value === "dialog" || attribute.value.value === "alertdialog") &&
            !OWNERS.dialog.test(file)
          ) {
            context.report({ node, messageId: "ownDialog" });
          }
        }
      },
      JSXElement(node) {
        const opening = node.openingElement;
        if (
          opening.name.type !== "JSXIdentifier" ||
          opening.name.name !== "Banner" ||
          OWNERS.states.test(file)
        ) {
          return;
        }
        const usesRefusal = node.children.some(
          (child) =>
            child.type === "JSXExpressionContainer" &&
            child.expression.type === "CallExpression" &&
            child.expression.callee.type === "Identifier" &&
            child.expression.callee.name === "refusal",
        );
        if (usesRefusal) {
          context.report({ node, messageId: "ownProblem" });
        }
      },
    };
  },
};
