// Builds every site of the web app (ADR 0058, src/sites/sites.ts), one after another, each into its own folder: the
// bundle (vite.config.ts, with IDLEO_WEB_SITE), the module that pre-renders its screens, then scripts/postbuild.ts.
// IDLEO_WEB_SITES=app,host picks which; every site by default. The website is its own build (`pnpm build:site`).
import { spawnSync } from "node:child_process";
import { distOf, isSiteName, SITES } from "../src/sites/sites.ts";

const wanted = (process.env.IDLEO_WEB_SITES ?? SITES.join(","))
  .split(",")
  .map((name) => name.trim())
  .filter(Boolean);
for (const name of wanted) {
  if (!isSiteName(name)) {
    console.error(`IDLEO_WEB_SITES names ${name}; the sites are ${SITES.join(", ")}`);
    process.exit(2);
  }
}

function run(command: string, args: string[], env: NodeJS.ProcessEnv): void {
  const result = spawnSync(command, args, { stdio: "inherit", env });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

for (const site of wanted.filter(isSiteName)) {
  const env = { ...process.env, IDLEO_WEB_SITE: site };
  const dist = distOf(site, process.env.IDLEO_DIST);
  console.log(`\nbuilding the ${site} site into ${dist}/`);
  run("pnpm", ["exec", "vite", "build"], env);
  run("pnpm", ["exec", "vite", "build", "--ssr", "src/prerender.tsx", "--outDir", `${dist}-ssr`], env);
  run("node", ["scripts/postbuild.ts"], env);
}
