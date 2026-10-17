/**
 * What a build may be configured with (ADR 0018). Checked when vite.config.ts loads, so a wrong combination
 * never produces a bundle. Above all, only a local build may use the OIDC mock sign-in adapter: the mock
 * server signs in anyone as anyone.
 */

export type Deployment = "local" | "staging" | "production";
export type AuthAdapterName = "firebase" | "oidc-mock";

export interface BuildEnv {
  IDLEO_DEPLOYMENT?: string | undefined;
  VITE_AUTH?: string | undefined;
  VITE_API_URL?: string | undefined;
  /** Object storage, which the browser uploads inputs to straight from the page (ADR 0019). */
  VITE_STORAGE_URL?: string | undefined;
  /** Where the add-a-machine screen sends owners for the install steps; the repository's runbook if left out. */
  VITE_HOST_RUNBOOK_URL?: string | undefined;
  /** The public site's own address, for its sitemap and canonical links (ADR 0029); a local build may leave it out. */
  IDLEO_SITE_URL?: string | undefined;
  /** The app's own address, where the website's "Log in" and "Get started" lead (ADR 0029); a local build may leave it out. */
  IDLEO_APP_URL?: string | undefined;
  VITE_OIDC_ISSUER?: string | undefined;
  VITE_OIDC_CLIENT_ID?: string | undefined;
  VITE_FIREBASE_API_KEY?: string | undefined;
  VITE_FIREBASE_AUTH_DOMAIN?: string | undefined;
  VITE_FIREBASE_PROJECT_ID?: string | undefined;
}

const LOCAL_HOSTS = ["localhost", "127.0.0.1", "[::1]"];

function isLocalOrPlain(url: string): boolean {
  try {
    const parsed = new URL(url);
    return parsed.protocol !== "https:" || LOCAL_HOSTS.includes(parsed.hostname);
  } catch {
    return true;
  }
}

/**
 * The two addresses a deployed build needs (ADR 0029): the website's, `IDLEO_SITE_URL`, and the app's, `IDLEO_APP_URL`,
 * both https and not on this machine. A local build may leave both out. The app's build and the website's both check
 * them, since each links to the other.
 */
export function siteSettingsProblems(env: BuildEnv): string[] {
  const problems: string[] = [];
  const deployment = env.IDLEO_DEPLOYMENT ?? "local";
  if (deployment === "local") {
    return problems;
  }
  if (!env.IDLEO_SITE_URL) {
    problems.push(`a ${deployment} build needs IDLEO_SITE_URL, the public site's address`);
  } else if (isLocalOrPlain(env.IDLEO_SITE_URL)) {
    problems.push(`a ${deployment} build needs an https site address that isn't on this machine`);
  }
  if (!env.IDLEO_APP_URL) {
    problems.push(`a ${deployment} build needs IDLEO_APP_URL, the app's address`);
  } else if (isLocalOrPlain(env.IDLEO_APP_URL)) {
    problems.push(`a ${deployment} build needs an https app address that isn't on this machine`);
  }
  return problems;
}

/** Every problem with these settings; empty when the build may go ahead. */
export function buildSettingsProblems(env: BuildEnv): string[] {
  const problems: string[] = [];
  const deployment = env.IDLEO_DEPLOYMENT ?? "local";
  if (!["local", "staging", "production"].includes(deployment)) {
    problems.push(`IDLEO_DEPLOYMENT must be local, staging or production, not ${deployment}`);
  }
  const auth = env.VITE_AUTH ?? "";
  if (!["firebase", "oidc-mock"].includes(auth)) {
    problems.push("VITE_AUTH must be firebase or oidc-mock");
  }
  if (auth === "oidc-mock" && deployment !== "local") {
    problems.push(`a ${deployment} build can't use the oidc-mock sign-in adapter; use firebase`);
  }
  if (!env.VITE_API_URL) {
    problems.push("VITE_API_URL is missing");
  } else if (deployment !== "local" && isLocalOrPlain(env.VITE_API_URL)) {
    problems.push(`a ${deployment} build needs an https API that isn't on this machine`);
  }
  if (!env.VITE_STORAGE_URL) {
    problems.push("VITE_STORAGE_URL is missing");
  } else if (deployment !== "local" && isLocalOrPlain(env.VITE_STORAGE_URL)) {
    problems.push(`a ${deployment} build needs https storage that isn't on this machine`);
  }
  problems.push(...siteSettingsProblems(env));
  if (auth === "oidc-mock" && (!env.VITE_OIDC_ISSUER || !env.VITE_OIDC_CLIENT_ID)) {
    problems.push("the oidc-mock adapter needs VITE_OIDC_ISSUER and VITE_OIDC_CLIENT_ID");
  }
  if (
    auth === "firebase" &&
    (!env.VITE_FIREBASE_API_KEY || !env.VITE_FIREBASE_AUTH_DOMAIN || !env.VITE_FIREBASE_PROJECT_ID)
  ) {
    problems.push(
      "the firebase adapter needs VITE_FIREBASE_API_KEY, VITE_FIREBASE_AUTH_DOMAIN and VITE_FIREBASE_PROJECT_ID",
    );
  }
  return problems;
}

export function assertBuildSettings(env: BuildEnv): void {
  const problems = buildSettingsProblems(env);
  if (problems.length > 0) {
    throw new Error(`Refusing to build the web app: ${problems.join("; ")}`);
  }
}
