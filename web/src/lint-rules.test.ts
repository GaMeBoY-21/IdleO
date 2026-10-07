// @vitest-environment node
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

/** The lint rules ADR 0018 relies on actually fire (eslint.config.js). */
const eslint = new ESLint({ cwd: new URL("..", import.meta.url).pathname });

async function rules(code: string, filePath: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath });
  return result!.messages.map((message) => message.ruleId ?? message.message);
}

describe("the design-token rule", () => {
  it("refuses raw colours and arbitrary values in components", async () => {
    expect(await rules('export const a = "text-[#ff0000]";\n', "src/lib/cn.ts")).toContain(
      "idleo/design-tokens",
    );
    expect(await rules('export const a = "p-[13px] text-text";\n', "src/lib/cn.ts")).toContain(
      "idleo/design-tokens",
    );
    expect(await rules('export const a = "rgb(1 2 3)";\n', "src/lib/cn.ts")).toContain("idleo/design-tokens");
  });

  it("allows token classes and bracketed variants", async () => {
    expect(
      await rules(
        'export const a = "bg-surface text-text p-4 data-[state=open]:bg-sunken";\n',
        "src/lib/cn.ts",
      ),
    ).not.toContain("idleo/design-tokens");
  });

  it("lets the tokens file name colours", async () => {
    expect(await rules('export const a = "#FFFFFF";\n', "src/design/tokens.ts")).not.toContain(
      "idleo/design-tokens",
    );
  });
});

describe("the sign-in SDK rule", () => {
  it("refuses the Firebase SDK and oidc-client-ts outside src/auth", async () => {
    expect(
      await rules('import { getAuth } from "firebase/auth";\nexport const a = getAuth;\n', "src/lib/cn.ts"),
    ).toContain("no-restricted-imports");
    expect(
      await rules(
        'import { UserManager } from "oidc-client-ts";\nexport const a = UserManager;\n',
        "src/api/client.ts",
      ),
    ).toContain("no-restricted-imports");
  });

  it("allows them inside src/auth", async () => {
    expect(
      await rules(
        'import { getAuth } from "firebase/auth";\nexport const a = getAuth;\n',
        "src/auth/firebase.ts",
      ),
    ).not.toContain("no-restricted-imports");
  });
});
