// The web image's nginx server block for one build folder (web/Dockerfile, ADR 0045): the same rules scripts/serve.ts
// applies, and the deployment runbook gives every host. The security headers from headers.json on every response, a
// page in path-headers.json (the Credit page, ADR 0039) with its own instead, a permanent redirect for each path in
// redirects.json, a screen's page for an address under its folder in rewrites.json (a link's id), a folder's
// index.html for its path, 404.html with a 404, and long caching only for hashed assets.
//
//   node scripts/nginx-config.ts <build folder> <port>      prints the server block
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

type Headers = Record<string, string>;

export interface ServeRules {
  headers: Headers;
  pathHeaders: Record<string, Headers>;
  redirects: Record<string, string>;
  rewrites: Record<string, string>;
}

/** The files the build writes beside its pages, which are rules for the host, never served. */
export const RULE_FILES = ["headers.json", "path-headers.json", "redirects.json", "rewrites.json"];

/** The rules a build folder carries; only headers.json is required. */
export function readRules(dist: string): ServeRules {
  const read = <T>(name: string, fallback: T): T =>
    existsSync(join(dist, name)) ? (JSON.parse(readFileSync(join(dist, name), "utf8")) as T) : fallback;
  return {
    headers: read<Headers>("headers.json", {}),
    pathHeaders: read<Record<string, Headers>>("path-headers.json", {}),
    redirects: read<Record<string, string>>("redirects.json", {}),
    rewrites: read<Record<string, string>>("rewrites.json", {}),
  };
}

/** A value inside nginx's double quotes: nginx has no escape for `$` (a variable) or `"`, so neither may appear. */
function quoted(value: string): string {
  if (/["$\\\n\r]/.test(value)) {
    throw new Error(`can't put ${JSON.stringify(value)} in nginx's configuration`);
  }
  return `"${value}"`;
}

function path(value: string): string {
  if (!/^\/[A-Za-z0-9._~/-]*$/.test(value)) {
    throw new Error(`${JSON.stringify(value)} isn't a plain path`);
  }
  return value;
}

/** `add_header` for every header, sent with errors and redirects too. */
function addHeaders(headers: Headers, indent: string): string[] {
  return Object.entries(headers).map(
    ([name, value]) => `${indent}add_header ${quoted(name)} ${quoted(value)} always;`,
  );
}

/**
 * The server block. nginx inherits add_header only into a location that has none of its own, so each location
 * repeats the headers it needs: the build's, or a page's own.
 */
export function serverBlock(rules: ServeRules, root: string, port: number): string {
  const all = (indent: string, cache: string) => [
    ...addHeaders(rules.headers, indent),
    `${indent}add_header Cache-Control ${quoted(cache)} always;`,
  ];
  const lines = [
    "server {",
    `    listen ${port};`,
    `    root ${path(root)};`,
    "    index index.html;",
    "    # A folder's address without its slash is sent to it with one, relative, whatever host and port the ingress has.",
    "    absolute_redirect off;",
    "    server_tokens off;",
    "    gzip on;",
    "    gzip_types text/css text/javascript application/javascript application/json application/xml image/svg+xml text/plain;",
    "    error_page 404 /404.html;",
    "",
    "    location / {",
    ...all("        ", "no-cache"),
    "        try_files $uri $uri/ =404;",
    "    }",
    "    location = /404.html {",
    ...all("        ", "no-cache"),
    "    }",
    "    # Hashed, so a new build is a new name: cached for a year.",
    "    location ^~ /assets/ {",
    ...all("        ", "public, max-age=31536000, immutable"),
    "    }",
    "    location ^~ /site-assets/ {",
    ...all("        ", "public, max-age=31536000, immutable"),
    "    }",
  ];
  for (const name of RULE_FILES) {
    lines.push(`    location = /${name} {`, "        return 404;", "    }");
  }
  for (const [page, headers] of Object.entries(rules.pathHeaders)) {
    const index = page.endsWith("/") ? `${page}index.html` : page;
    for (const at of new Set([page, index])) {
      lines.push(
        `    location = ${path(at)} {`,
        ...addHeaders(headers, "        "),
        `        add_header Cache-Control "no-cache" always;`,
        `        try_files ${path(index)} =404;`,
        "    }",
      );
    }
  }
  for (const [from, to] of Object.entries(rules.redirects)) {
    lines.push(
      `    location = ${path(from)} {`,
      ...addHeaders(rules.headers, "        "),
      `        add_header Cache-Control "public, max-age=3600" always;`,
      `        return 301 ${path(to)};`,
      "    }",
    );
  }
  for (const [prefix, page] of Object.entries(rules.rewrites)) {
    lines.push(
      `    location ^~ ${path(prefix)} {`,
      ...all("        ", "no-cache"),
      `        try_files ${path(page)} =404;`,
      "    }",
    );
  }
  lines.push("}", "");
  return lines.join("\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [dist, port] = process.argv.slice(2);
  if (!dist || !port) {
    console.error("usage: node scripts/nginx-config.ts <build folder> <port>");
    process.exit(2);
  }
  process.stdout.write(
    serverBlock(readRules(dist), "/usr/share/nginx/" + dist.replace(/\/$/, ""), Number(port)),
  );
}
