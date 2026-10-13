// The API reference, as Markdown, from api/openapi.yaml when the site is built (ADR 0029): every operation of the
// renter and host API, with its parameters, body and answers, and every schema they use. The admin console's
// operations (`/admin/v1/…`, ADR 0027) are for the people who run the service and aren't in it; a test says so.
import { readFileSync } from "node:fs";
import { parse } from "yaml";

type Schema = {
  $ref?: string;
  type?: string | string[];
  format?: string;
  enum?: unknown[];
  items?: Schema;
  oneOf?: Schema[];
  anyOf?: Schema[];
  allOf?: Schema[];
  properties?: Record<string, Schema & { description?: string }>;
  required?: string[];
  description?: string;
  additionalProperties?: Schema | boolean;
};
interface Parameter {
  name: string;
  in: string;
  required?: boolean;
  description?: string;
  schema?: Schema;
}
interface Operation {
  operationId: string;
  tags?: string[];
  summary?: string;
  description?: string;
  parameters?: Parameter[];
  requestBody?: { required?: boolean; content: Record<string, { schema?: Schema }> };
  responses: Record<string, { description?: string; content?: Record<string, { schema?: Schema }> }>;
}
interface Spec {
  info: { title: string; description?: string };
  paths: Record<string, Record<string, unknown>>;
  components: { schemas: Record<string, Schema> };
}

const METHODS = ["get", "post", "put", "patch", "delete"] as const;

export interface ApiOperation {
  id: string;
  method: string;
  path: string;
  tag: string;
}

export function loadSpec(path: string): Spec {
  return parse(readFileSync(path, "utf8")) as Spec;
}

/** The operations a reader of the public docs may call, in the contract's own order. */
export function publicOperations(spec: Spec): (ApiOperation & { operation: Operation })[] {
  return Object.entries(spec.paths).flatMap(([path, item]) =>
    path.startsWith("/admin/")
      ? []
      : METHODS.flatMap((method) => {
          const operation = item[method] as Operation | undefined;
          return operation
            ? [
                {
                  id: operation.operationId,
                  method: method.toUpperCase(),
                  path,
                  tag: operation.tags?.[0] ?? "other",
                  operation,
                },
              ]
            : [];
        }),
  );
}

const refName = (ref: string): string => ref.slice(ref.lastIndexOf("/") + 1);

/** "sessionCreate" → "Session create"; "cliLogin" → "Cli login". */
const words = (name: string): string => {
  const spaced = name.replace(/([a-z0-9])([A-Z])/g, "$1 $2").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
};

function typeOf(schema: Schema | undefined): string {
  if (!schema) {
    return "any";
  }
  if (schema.$ref) {
    const name = refName(schema.$ref);
    return `[${name}](#schema-${name})`;
  }
  const choices = schema.oneOf ?? schema.anyOf;
  if (choices) {
    return choices.map(typeOf).join(" or ");
  }
  if (schema.allOf) {
    return schema.allOf.map(typeOf).join(" and ");
  }
  const type = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  const shown = type.map((one) => {
    if (one === "array") {
      return `array of ${typeOf(schema.items)}`;
    }
    if (schema.enum) {
      return `${one}: ${schema.enum.map((value) => `\`${String(value)}\``).join(", ")}`;
    }
    if (one === "object" && typeof schema.additionalProperties === "object") {
      return `map of ${typeOf(schema.additionalProperties)}`;
    }
    return schema.format ? `${one} (${schema.format})` : one;
  });
  return shown.join(" or ") || "object";
}

const cell = (text: string | undefined): string =>
  (text ?? "").replace(/\s+/g, " ").replace(/\|/g, "\\|").trim();

/** Every schema name an operation reaches, directly or through other schemas. */
function reachable(spec: Spec, start: Iterable<string>): Set<string> {
  const seen = new Set<string>();
  const visit = (value: unknown): void => {
    if (Array.isArray(value)) {
      value.forEach(visit);
    } else if (value && typeof value === "object") {
      for (const [key, inner] of Object.entries(value)) {
        if (key === "$ref" && typeof inner === "string") {
          const name = refName(inner);
          if (!seen.has(name)) {
            seen.add(name);
            visit(spec.components.schemas[name]);
          }
        } else {
          visit(inner);
        }
      }
    }
  };
  for (const name of start) {
    if (!seen.has(name)) {
      seen.add(name);
      visit(spec.components.schemas[name]);
    }
  }
  return seen;
}

function bodyOf(content: Record<string, { schema?: Schema }> | undefined): string {
  const entry = content ? Object.entries(content)[0] : undefined;
  return entry ? `${typeOf(entry[1].schema)} (\`${entry[0]}\`)` : "no body";
}

function operationSection(item: ApiOperation & { operation: Operation }): string {
  const { operation } = item;
  const lines = [
    `<h4 id="op-${item.id}"><span class="method">${item.method}</span> <code>${item.path}</code></h4>`,
    "",
    `${words(item.id)}. Operation \`${item.id}\`.${operation.description ? ` ${operation.description}` : ""}`,
    "",
  ];
  if (operation.parameters?.length) {
    lines.push("| Parameter | In | Type | Required | What it is |", "| --- | --- | --- | --- | --- |");
    for (const parameter of operation.parameters) {
      lines.push(
        `| \`${parameter.name}\` | ${parameter.in} | ${typeOf(parameter.schema)} | ${parameter.required ? "yes" : "no"} | ${cell(parameter.description)} |`,
      );
    }
    lines.push("");
  }
  if (operation.requestBody) {
    lines.push(
      `Request body: ${bodyOf(operation.requestBody.content)}${operation.requestBody.required ? "" : ", optional"}.`,
      "",
    );
  }
  lines.push("| Answer | What it is |", "| --- | --- |");
  for (const [status, response] of Object.entries(operation.responses)) {
    const body = response.content ? bodyOf(response.content) : "no body";
    lines.push(`| ${status === "default" ? "any error" : status} | ${cell(response.description)}: ${body} |`);
  }
  lines.push("");
  return lines.join("\n");
}

function schemaSection(spec: Spec, name: string): string {
  const schema = spec.components.schemas[name]!;
  const lines = [`<h3 id="schema-${name}"><code>${name}</code></h3>`, ""];
  if (schema.description) {
    lines.push(cell(schema.description), "");
  }
  if (schema.properties) {
    lines.push("| Field | Type | Required | What it is |", "| --- | --- | --- | --- |");
    for (const [field, property] of Object.entries(schema.properties)) {
      lines.push(
        `| \`${field}\` | ${typeOf(property)} | ${schema.required?.includes(field) ? "yes" : "no"} | ${cell(property.description)} |`,
      );
    }
  } else {
    lines.push(`Type: ${typeOf(schema)}.`);
  }
  lines.push("");
  return lines.join("\n");
}

/** The whole reference page's body. */
export function apiReference(spec: Spec): string {
  const operations = publicOperations(spec);
  const tags = [...new Set(operations.map((item) => item.tag))];
  const used = reachable(
    spec,
    operations.flatMap(({ operation }) => {
      const names: string[] = [];
      const collect = (value: unknown): void => {
        if (Array.isArray(value)) {
          value.forEach(collect);
        } else if (value && typeof value === "object") {
          for (const [key, inner] of Object.entries(value)) {
            if (key === "$ref" && typeof inner === "string") {
              names.push(refName(inner));
            } else {
              collect(inner);
            }
          }
        }
      };
      collect(operation);
      return names;
    }),
  );
  const out = [
    "This is the API the web app and the command line use. Everything is under `/v1`, in JSON. Money is whole paise (a rupee is 100); times are UTC.",
    "",
    "- **Signing in:** send `Authorization: Bearer <token>`. The token is an ID token from the sign-in provider, or an API key, which starts `idleo_` and is made in your account. The first call with a new token creates your account.",
    "- **Errors** are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details, `application/problem+json`, with a `type` like `/problems/insufficient-credit` that never changes.",
    "- **Creating things:** calls that create send an `Idempotency-Key` header, so a repeat never creates twice.",
    "- **Limits:** a person's requests are limited each minute, and the answer to going over is `429` with `Retry-After`.",
    "",
    `The operations are grouped by what they act on: ${tags.map((tag) => `[${words(tag)}](#tag-${tag})`).join(", ")}. The schemas they use are at the end.`,
    "",
    "## Operations",
    "",
  ];
  for (const tag of tags) {
    out.push(`<h3 id="tag-${tag}">${words(tag)}</h3>`, "");
    for (const item of operations.filter((candidate) => candidate.tag === tag)) {
      out.push(operationSection(item));
    }
  }
  out.push("## Schemas", "");
  for (const name of Object.keys(spec.components.schemas).filter((candidate) => used.has(candidate))) {
    out.push(schemaSection(spec, name));
  }
  return out.join("\n");
}
