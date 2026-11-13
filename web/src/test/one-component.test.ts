import { RuleTester } from "eslint";
import tseslint from "typescript-eslint";
import { afterAll, describe, it } from "vitest";
// @ts-expect-error an ESLint rule, plain JavaScript with no types
import rule from "../../eslint-rules/one-component.js";

Object.assign(RuleTester, { afterAll, describe, it });

const tester = new RuleTester({
  languageOptions: { parser: tseslint.parser, parserOptions: { ecmaFeatures: { jsx: true } } },
});

const screen = "/web/src/features/account/Thing.tsx";

tester.run("one-component", rule as never, {
  valid: [
    { filename: "/web/src/components/ui/Headers.tsx", code: `const x = <h1>Title</h1>;` },
    { filename: "/web/src/components/ui/DataTable.tsx", code: `const x = <table />;` },
    { filename: "/web/src/components/ui/KeyValues.tsx", code: `const x = <dl />;` },
    { filename: "/web/src/components/ui/Sheet.tsx", code: `import { Dialog } from "radix-ui";` },
    { filename: "/web/src/components/ui/Button.tsx", code: `const c = "size-4 animate-spin";` },
    {
      filename: "/web/src/components/ui/StatusChip.tsx",
      code: `const c = "size-1.5 rounded-full bg-success";`,
    },
    { filename: "/web/src/site/pages/Landing.tsx", code: `const x = <h1>The website keeps its own</h1>;` },
    { filename: screen, code: `const x = <PageHeader title="Keys" />; const y = <h2>Fine</h2>;` },
    { filename: screen, code: `const c = "rounded-full bg-surface-3";` },
  ],
  invalid: [
    { filename: screen, code: `const x = <h1>Mine</h1>;`, errors: [{ messageId: "h1" }] },
    { filename: screen, code: `const x = <table />;`, errors: [{ messageId: "table" }] },
    { filename: screen, code: `const x = <div role="table" />;`, errors: [{ messageId: "table" }] },
    { filename: screen, code: `const x = <dl />;`, errors: [{ messageId: "dl" }] },
    { filename: screen, code: `import { Dialog } from "radix-ui";`, errors: [{ messageId: "dialog" }] },
    { filename: screen, code: `const x = <div role="dialog" />;`, errors: [{ messageId: "dialog" }] },
    { filename: screen, code: `const c = "size-4 animate-spin";`, errors: [{ messageId: "spinner" }] },
    {
      filename: screen,
      code: "const c = `px-2 rounded-full bg-danger`;",
      errors: [{ messageId: "chip" }],
    },
  ],
});
