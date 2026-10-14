import js from "@eslint/js";
import { defineConfig } from "eslint/config";
import jsxA11y from "eslint-plugin-jsx-a11y";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";
import designTokens from "./eslint-rules/design-tokens.js";

const SIGN_IN_SDKS = ["firebase", "firebase/*", "@firebase/*", "oidc-client-ts"];

export default defineConfig(
  {
    ignores: [
      "dist",
      "dist-ssr",
      "dist-site",
      "dist-site-ssr",
      "dist-firebase",
      "dist-local",
      "dist-site-local",
      "node_modules",
      "src/api/schema.gen.ts",
      "test-results",
      "playwright-report",
      ".lighthouseci",
    ],
  },
  js.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    extends: [tseslint.configs.strictTypeChecked],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: {
      "react-hooks": reactHooks,
      "jsx-a11y": jsxA11y,
      idleo: { rules: { "design-tokens": designTokens } },
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.strict.rules,
      "idleo/design-tokens": "error",
      // ADR 0018: the provider SDKs are used only inside src/auth/.
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            { group: SIGN_IN_SDKS, message: "Sign-in SDKs are used only inside src/auth/ (ADR 0018)." },
          ],
        },
      ],
      "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true }],
      "@typescript-eslint/no-confusing-void-expression": ["error", { ignoreArrowShorthand: true }],
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },
  {
    files: ["**/*.js"],
    languageOptions: { globals: globals.node },
  },
  {
    // Test doubles are async without awaiting, and assertions read mocks off objects.
    files: ["**/*.test.{ts,tsx}", "src/test/**"],
    rules: { "@typescript-eslint/require-await": "off", "@typescript-eslint/unbound-method": "off" },
  },
  {
    files: ["src/auth/**"],
    rules: { "no-restricted-imports": "off" },
  },
  {
    // The only places that may name colours: the tokens, the brand (its accent), and Google's own mark.
    files: [
      "src/design/tokens.ts",
      "src/brand/**",
      "src/features/sign-in/GoogleMark.tsx",
      "**/*.test.ts",
      "**/*.test.tsx",
    ],
    rules: { "idleo/design-tokens": "off" },
  },
  {
    files: ["eslint-rules/**", "*.config.{js,ts}", "scripts/**", "e2e/**"],
    rules: { "idleo/design-tokens": "off" },
  },
);
