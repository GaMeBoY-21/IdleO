// A build's permanent redirects (redirects.json, ADR 0058): one rule, which scripts/serve.ts and the sites' own links
// (src/lib/site-links.ts) follow, and scripts/nginx-config.ts and scripts/pages-config.ts write out for nginx and Cloudflare Pages. A key is a path; a key
// ending in `*` is a prefix, and what follows it is kept in the target, which then ends in `*` too
// (`"/link/*": "https://host.idleo.in/link/*"`). The target is a path on the same site or an address on another. The
// query is always kept, so `/hosting/machine/?id=1` arrives as `/laptop/?id=1`. A path that ends in a slash is
// redirected without its slash too, as a static host would first add one.

/** A plain path, as every key is. */
export const PLAIN_PATH = /^\/[A-Za-z0-9._~/-]*$/;
/** A target: a plain path, or the same on an http(s) address, with an optional `*` at the end of a prefix's. */
const TARGET = /^(https?:\/\/[A-Za-z0-9.-]+(:\d+)?)?\/[A-Za-z0-9._~/-]*\*?$/;

export interface Redirect {
  /** The path, or the prefix without its `*`. */
  from: string;
  prefix: boolean;
  /** The target, or its prefix without its `*`. */
  to: string;
}

/** The rules in redirects.json, checked: a prefix only to a prefix, every path and address plain. */
export function parseRedirects(redirects: Record<string, string>): Redirect[] {
  return Object.entries(redirects).map(([key, target]) => {
    const prefix = key.endsWith("*");
    const from = prefix ? key.slice(0, -1) : key;
    if (!PLAIN_PATH.test(from)) {
      throw new Error(`${JSON.stringify(key)} isn't a plain path`);
    }
    if (!TARGET.test(target) || target.endsWith("*") !== prefix) {
      throw new Error(`${JSON.stringify(target)} isn't a plain target for ${JSON.stringify(key)}`);
    }
    return { from, prefix, to: prefix ? target.slice(0, -1) : target };
  });
}

/** Where a request for `path` with `search` (`?id=1`, or empty) is sent, or null when it isn't redirected. */
export function redirectFor(rules: Redirect[], path: string, search: string): string | null {
  for (const rule of rules) {
    if (!rule.prefix && (path === rule.from || (rule.from.endsWith("/") && `${path}/` === rule.from))) {
      return `${rule.to}${search}`;
    }
  }
  for (const rule of rules) {
    if (rule.prefix && path.startsWith(rule.from)) {
      return `${rule.to}${path.slice(rule.from.length)}${search}`;
    }
  }
  return null;
}
