// Everything the public pages need that only the repository knows (ADR 0029): the services and their worked examples
// from the catalog, and the docs. Used by scripts/build-site.ts, and by the tests, which draw the same pages.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { SiteContext } from "../../src/site/context.ts";
import { loadServices, workedExamples } from "./catalog.ts";
import { buildDocs } from "./docs.ts";

export const repository = (path: string): string =>
  fileURLToPath(new URL(`../../../${path}`, import.meta.url));

export function siteContext(apiUrl: string | undefined): SiteContext {
  const catalog = repository("templates/catalog.yaml");
  const development = repository("templates/development.yaml");
  const services = loadServices(catalog, development);
  return {
    apiUrl: apiUrl ? new URL(apiUrl).origin : null,
    services,
    examples: workedExamples(catalog, development),
    docs: buildDocs({
      docsDir: repository("docs/site"),
      cliMarkdown: readFileSync(repository("docs/site/generated/cli.md"), "utf8"),
      openapiPath: repository("api/openapi.yaml"),
      services,
    }),
  };
}
