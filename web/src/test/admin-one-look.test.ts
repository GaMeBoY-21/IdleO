import { RuleTester } from "eslint";
import tseslint from "typescript-eslint";
import { afterAll, describe, it } from "vitest";
// @ts-expect-error an ESLint rule, plain JavaScript with no types
import rule from "../../eslint-rules/admin-one-look.js";

Object.assign(RuleTester, { afterAll, describe, it });

const tester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

const screen = "src/features/admin/Thing.tsx";

tester.run("admin-one-look", rule as never, {
  valid: [
    { filename: "src/features/admin/Sheet.tsx", code: `import { Dialog } from "radix-ui";` },
    { filename: screen, code: `import { Tabs } from "radix-ui";` },
    { filename: screen, code: `const x = <Problem error={problem} />;` },
    { filename: screen, code: `const confirm = () => 1; confirm();` },
    {
      filename: "src/features/account/Thing.tsx",
      code: `import { Dialog } from "radix-ui"; confirm("sure?");`,
    },
    { filename: "src/features/admin/ui.tsx", code: `import { EmptyState } from "@/components/ui/States";` },
  ],
  invalid: [
    { filename: screen, code: `import { Dialog } from "radix-ui";`, errors: [{ messageId: "ownDialog" }] },
    { filename: screen, code: `window.confirm("sure?");`, errors: [{ messageId: "browserDialog" }] },
    { filename: screen, code: `confirm("sure?");`, errors: [{ messageId: "browserDialog" }] },
    { filename: screen, code: `const x = <div role="dialog" />;`, errors: [{ messageId: "ownDialog" }] },
    { filename: screen, code: `const x = <dialog />;`, errors: [{ messageId: "ownDialog" }] },
    {
      filename: screen,
      code: `import { EmptyState } from "@/components/ui/States";`,
      errors: [{ messageId: "ownState" }],
    },
    {
      filename: screen,
      code: `const x = <Banner tone="danger">{refusal(problem)}</Banner>;`,
      errors: [{ messageId: "ownProblem" }],
    },
  ],
});
