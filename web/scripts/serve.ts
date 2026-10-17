// Serves a build folder (dist/ for the app, dist-website/ for the website: IDLEO_DIST and PORT) the way a host or CDN
// should (ADR 0018): the security headers from its headers.json on every response, a permanent redirect for each path
// in its redirects.json (the website's old landing page), a folder's index.html for its path, 404.html with a 404, text pre-compressed once (Brotli, else gzip)
// as a CDN serves it, and long caching only for hashed assets. Used by the end-to-end test and Lighthouse;
// production picks its host with deployment.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { brotliCompressSync, constants, gzipSync } from "node:zlib";

const dist = fileURLToPath(new URL(`../${process.env.IDLEO_DIST ?? "dist"}/`, import.meta.url));
const port = Number(process.env.PORT ?? 3000);
const headers = JSON.parse(readFileSync(join(dist, "headers.json"), "utf8")) as Record<string, string>;
const redirects = existsSync(join(dist, "redirects.json"))
  ? (JSON.parse(readFileSync(join(dist, "redirects.json"), "utf8")) as Record<string, string>)
  : {};
const types: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".woff2": "font/woff2",
  ".json": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".xml": "application/xml; charset=utf-8",
};

interface Served {
  type: string;
  raw: Buffer;
  br?: Buffer;
  gzip?: Buffer;
}

/** Every file, read and compressed once at startup. */
const files = new Map<string, Served>();
function load(directory: string): void {
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) {
      load(path);
      continue;
    }
    const type = types[extname(path)] ?? "application/octet-stream";
    const raw = readFileSync(path);
    const compressible = /^(text\/|application\/(json|xml)|image\/svg)/.test(type);
    files.set(`/${relative(dist, path)}`, {
      type,
      raw,
      ...(compressible
        ? {
            br: brotliCompressSync(raw, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }),
            gzip: gzipSync(raw, { level: 9 }),
          }
        : {}),
    });
  }
}
load(dist);

createServer((request, response) => {
  const path = decodeURIComponent(new URL(request.url ?? "/", "http://localhost").pathname);
  const moved = redirects[path];
  if (moved) {
    response.writeHead(301, { ...headers, Location: moved, "Cache-Control": "public, max-age=3600" });
    response.end();
    return;
  }
  let file = files.get(path.endsWith("/") ? `${path}index.html` : path);
  let status = 200;
  if (!file || path === "/headers.json" || path === "/redirects.json") {
    file = files.get("/404.html")!;
    status = 404;
  }
  const accepts = request.headers["accept-encoding"] ?? "";
  const encoding =
    file.br && accepts.includes("br") ? "br" : file.gzip && accepts.includes("gzip") ? "gzip" : null;
  const body = encoding === "br" ? file.br! : encoding === "gzip" ? file.gzip! : file.raw;
  response.writeHead(status, {
    ...headers,
    "Content-Type": file.type,
    "Content-Length": body.length,
    "Cache-Control":
      path.startsWith("/assets/") || path.startsWith("/site-assets/")
        ? "public, max-age=31536000, immutable"
        : "no-cache",
    Vary: "Accept-Encoding",
    ...(encoding ? { "Content-Encoding": encoding } : {}),
  });
  response.end(request.method === "HEAD" ? undefined : body);
}).listen(port, "127.0.0.1", () =>
  console.log(`serving ${relative(process.cwd(), dist) || "."} on http://localhost:${port}`),
);
