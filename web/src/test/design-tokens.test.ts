// @vitest-environment node
import { RuleTester } from "eslint";
import tseslint from "typescript-eslint";
import { afterAll, describe, it } from "vitest";
// @ts-expect-error an ESLint rule, plain JavaScript with no types
import rule from "../../eslint-rules/design-tokens.js";

Object.assign(RuleTester, { afterAll, describe, it });

const tester = new RuleTester({
  languageOptions: { parser: tseslint.parser, parserOptions: { ecmaFeatures: { jsx: true } } },
});

tester.run("design-tokens", rule as never, {
  valid: [
    { code: `const c = "p-4 gap-2 mt-1 h-56 w-72 max-w-64 z-sheet duration-appear";` },
    { code: `const c = "h-6.5 size-4.5";` },
    { code: `const c = "grid grid-cols-1 gap-4 xl:grid-cols-3";` },
    { code: `const c = "grid cols-cards gap-4";` },
    { code: `const x = <div style={{ width: "50%" }} />;` },
  ],
  invalid: [
    { code: `const c = "bg-[#123456]";`, errors: [{ messageId: "colour" }] },
    { code: `const c = "p-[13px]";`, errors: [{ messageId: "arbitrary" }] },
    { code: `const c = "gap-3";`, errors: [{ messageId: "grid" }] },
    { code: `const c = "z-40";`, errors: [{ messageId: "layer" }] },
    { code: `const c = "duration-200";`, errors: [{ messageId: "duration" }] },
    { code: `const c = "h-57";`, errors: [{ messageId: "step" }] },
    { code: `const c = "md:w-200";`, errors: [{ messageId: "step" }] },
    { code: `const c = "grid gap-4 xl:grid-cols-3";`, errors: [{ messageId: "columns" }] },
    { code: `const x = <div style={{ padding: 13 }} />;`, errors: [{ messageId: "styled" }] },
  ],
});
