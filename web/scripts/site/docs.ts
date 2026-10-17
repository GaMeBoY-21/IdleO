// The docs' pages (ADR 0029): the Markdown written by hand in docs/site/, and four pages generated from the code when
// the site is built, so they can't drift from it: the services from templates/catalog.yaml, the command line from the
// CLI's own definition (docs/site/generated/cli.md, which the CLI's tests keep current), the Python SDK from its
// docstrings (docs/site/generated/sdk.md, which the SDK's tests keep current) and the API from api/openapi.yaml.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Marked, type Tokens } from "marked";
import { parse } from "yaml";
import { brand } from "../../src/brand/brand.ts";
import type { DocPage, ServiceInfo } from "../../src/site/context.ts";
import { apiReference, loadSpec } from "./openapi.ts";

const slug = (text: string): string =>
  text
    .toLowerCase()
    .replace(/<[^>]*>/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");

/** Markdown to HTML, with an id on each heading so a section can be linked to. The text is our own, in this repository. */
const marked = new Marked({
  gfm: true,
  renderer: {
    heading(
      this: { parser: { parseInline: (tokens: Tokens.Generic[]) => string } },
      { tokens, depth, text }: Tokens.Heading,
    ) {
      return `<h${depth} id="${slug(text)}">${this.parser.parseInline(tokens)}</h${depth}>\n`;
    },
  },
});

/**
 * A table that's wider than the screen scrolls inside its own box, which a keyboard can reach and a screen reader names
 * (the box is a group with a label), so a wide API table never widens the page. A code block that's wider scrolls inside
 * itself, and a keyboard can reach it too.
 */
const scrolling = (html: string): string =>
  html
    .replaceAll(
      "<table>",
      '<div class="table-scroll" role="group" aria-label="Table, scrolls sideways" tabindex="0"><table>',
    )
    .replaceAll("</table>", "</table></div>")
    .replaceAll("<pre>", '<pre tabindex="0">');

export const toHtml = (markdown: string): string => scrolling(marked.parse(markdown, { async: false }));

/** The product's name, which hand-written pages write as `{{product}}`: one constant, `brand.name`, renames them all. */
export const PRODUCT_PLACEHOLDER = "{{product}}";
export const withProductName = (markdown: string): string =>
  markdown.replaceAll(PRODUCT_PLACEHOLDER, brand.name);
/** The app's address, which hand-written pages write as `{{app}}` for a link into it (the app has its own address). */
export const APP_PLACEHOLDER = "{{app}}";

interface Source {
  title: string;
  description: string;
  order: number;
  path: string;
  markdown: string;
}

function readSources(directory: string, appUrl: string): Source[] {
  return readdirSync(directory)
    .filter((file) => file.endsWith(".md") && file !== "README.md")
    .map((file) => {
      const text = readFileSync(join(directory, file), "utf8");
      const match = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(text);
      if (!match) {
        throw new Error(`docs/site/${file} has no front matter (title, description, order, path)`);
      }
      const meta = parse(match[1]!) as Partial<Source>;
      for (const key of ["title", "description", "order", "path"] as const) {
        if (meta[key] === undefined) {
          throw new Error(`docs/site/${file}'s front matter is missing ${key}`);
        }
      }
      return {
        ...(meta as Omit<Source, "markdown">),
        markdown: withProductName(match[2]!).replaceAll(APP_PLACEHOLDER, appUrl.replace(/\/+$/, "")),
      };
    });
}

/** What each service takes and gives, as Markdown. */
export function servicesMarkdown(services: ServiceInfo[]): string {
  const out = [
    "We provide the GPU. You bring your own model, data and code: none of these services supplies a model, and each one's inputs are yours. This page is made from the catalog of services, so it always matches what you can book.",
    "",
  ];
  for (const service of services) {
    out.push(
      `## ${service.name}`,
      "",
      `${service.tagline[0]!.toUpperCase()}${service.tagline.slice(1)}.${service.description ? ` ${service.description}` : ""}`,
      "",
    );
    if (service.inputs.length > 0) {
      out.push(
        "### What you bring",
        "",
        "| Input | What it is | How | Required | Up to |",
        "| --- | --- | --- | --- | --- |",
      );
      for (const input of service.inputs) {
        const how = input.accepts
          .map((accepts) => (accepts === "upload" ? "upload" : "https link"))
          .join(" or ");
        out.push(
          `| ${input.label} (\`${input.name}\`) | ${input.kind === "folder" ? "A folder, or a zip of one" : "One file"}. ${input.help} | ${how} | ${input.required ? "yes" : "no"} | ${input.maxSizeGb === null ? "" : `${input.maxSizeGb} GiB`} |`,
        );
      }
      out.push("");
    }
    if (service.questions.length > 0) {
      out.push("### What you choose", "");
      for (const question of service.questions) {
        out.push(
          `- **${question.label}**${question.choices.length ? `: ${question.choices.join("; ")}` : ""}${question.help ? `. ${question.help}` : ""}`,
        );
      }
      out.push("");
    }
    if (service.youGet) {
      out.push("### What you get", "", `${service.youGet[0]!.toUpperCase()}${service.youGet.slice(1)}.`, "");
    }
    if (service.source === "development") {
      out.push(
        "> The signed template for this service is still being finished, so the inputs above are those of the template it will be.",
        "",
      );
    }
  }
  out.push(
    "## How your inputs reach the job",
    "",
    "- An upload is put in the job's sandbox before it starts, in a folder named for the input, with your file paths kept.",
    "- A link is an https address the job fetches itself, inside its sandbox. We never fetch it for you.",
    "- Settings you choose reach the job as environment variables named `SESSION_PARAM_<NAME>`. Never put a secret in one.",
    "",
  );
  return out.join("\n");
}

export interface DocsInput {
  /** docs/site/ */
  docsDir: string;
  /** The CLI's reference, as the CLI wrote it. */
  cliMarkdown: string;
  /** The Python SDK's reference, as its own script wrote it. */
  sdkMarkdown: string;
  /** api/openapi.yaml */
  openapiPath: string;
  services: ServiceInfo[];
  /** The app's address (`IDLEO_APP_URL`), for the links into it. */
  appUrl: string;
}

/** Every docs page, in the order they're listed. */
export function buildDocs(input: DocsInput): DocPage[] {
  const hand = readSources(input.docsDir, input.appUrl);
  const generated: Source[] = [
    {
      title: "Services",
      description: "What each service takes and gives, made from the catalog of services.",
      order: 2,
      path: "/docs/services/",
      markdown: servicesMarkdown(input.services),
    },
    {
      title: "Command line",
      description: "Every command and flag of the command line, made from the program's own definition.",
      order: 3,
      path: "/docs/cli/",
      markdown: input.cliMarkdown,
    },
    {
      title: "Python SDK",
      description:
        "Run, follow and download sessions from a Python script or a notebook, made from the SDK's own code.",
      order: 4,
      path: "/docs/sdk/",
      markdown: input.sdkMarkdown,
    },
    {
      title: "API reference",
      description: "Every operation and schema of the renter and host API, made from the API contract.",
      order: 5,
      path: "/docs/api/",
      markdown: apiReference(loadSpec(input.openapiPath)),
    },
  ];
  return [...hand, ...generated]
    .sort((a, b) => a.order - b.order)
    .map((source) => ({
      path: source.path,
      title: source.title,
      description: source.description,
      html: toHtml(source.markdown),
    }));
}
