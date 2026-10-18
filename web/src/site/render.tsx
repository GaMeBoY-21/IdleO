import { renderToStaticMarkup } from "react-dom/server";
import { brand } from "@/brand/brand";
import type { PageDef, SiteContext } from "./context";
import type { EntryLinks } from "./paths";
import { Docs } from "./pages/Docs";
import { MAKERS } from "./help";
import { Earn } from "./pages/Earn";
import { Help, laptop } from "./pages/Help";
import { Gpus } from "./pages/Gpus";
import { Landing } from "./pages/Landing";
import { Placeholder } from "./pages/Placeholder";
import { Pricing } from "./pages/Pricing";
import { CONTACT, EARN, GPUS, HELP_VIRTUALIZATION, LANDING, PRICING, PRIVACY, TERMS } from "./paths";
import { Shell } from "./Shell";
import { band, column, heading, link, muted } from "./ui";

/**
 * Every public page (ADR 0029), by path: what its document says about it, and its body. scripts/build-site.ts writes
 * each into its folder, wraps it in the head (the style sheet, the CSP, the Open Graph tags) and gives it the one or
 * two small scripts it needs, if any. Nothing in here runs in the browser.
 */
export function pages(context: SiteContext): PageDef[] {
  const base = [
    {
      path: LANDING,
      title: `${brand.name}: rent a gaming GPU by the minute`,
      description:
        "Fine-tune a model or run your code on a gaming GPU in India, by the minute, in rupees. You bring your model, data and code; we provide the GPU.",
      body: <Landing context={context} />,
      islands: [],
    },
    {
      path: PRICING,
      title: `Pricing · ${brand.name}`,
      description:
        "What a GPU costs per hour by GPU memory, how billing works by the minute, and two typical examples.",
      body: <Pricing context={context} />,
      islands: ["pricing"],
    },
    {
      path: GPUS,
      title: `What each GPU can run · ${brand.name}`,
      description:
        "What 8, 12, 16 and 24 GB consumer GPUs typically run: fine-tuning with QLoRA, batches and models.",
      body: <Gpus links={context.links} />,
      islands: [],
    },
    {
      path: EARN,
      title: `Earn with your GPU · ${brand.name}`,
      description:
        "Rent out your RTX laptop or desktop while you aren't using it. How it stays safe, what you need, and an honest earnings estimate.",
      body: <Earn apiUrl={context.apiUrl} links={context.links} />,
      islands: ["earn"],
    },
    {
      path: TERMS,
      title: `Terms · ${brand.name}`,
      description: `The terms of using ${brand.name}, which are being drafted.`,
      body: (
        <Placeholder title="Terms" note="The terms will be here before the first outside tester signs in." />
      ),
      islands: [],
    },
    {
      path: PRIVACY,
      title: `Privacy · ${brand.name}`,
      description: `The privacy notice for ${brand.name}, which is being drafted.`,
      body: (
        <Placeholder
          title="Privacy"
          note="The privacy notice will be here before the first outside tester signs in."
        />
      ),
      islands: [],
    },
    {
      path: CONTACT,
      title: `Contact · ${brand.name}`,
      description: `How to reach ${brand.name}, which is being set up.`,
      body: (
        <Placeholder
          title="Contact"
          note="Contact details are being set up. Until then, ask the person who invited you."
        />
      ),
      islands: [],
    },
  ] satisfies PageDef[];
  const docs = context.docs.map((page) => ({
    path: page.path,
    title: `${page.title} · Docs · ${brand.name}`,
    description: page.description,
    body: <Docs page={page} pages={context.docs} />,
    islands: [] as PageDef["islands"],
  }));
  const help = MAKERS.map((maker) => ({
    path: `${HELP_VIRTUALIZATION}${maker.slug}/`,
    title: `Turn on virtualization · ${brand.name}`,
    description: `How to turn on virtualization in ${laptop(maker)}'s startup menu, which hosting needs.`,
    body: <Help maker={maker} />,
    islands: [] as PageDef["islands"],
  }));
  return [...base, ...docs, ...help];
}

/** One page's body as HTML inside the shared frame. */
export function renderPage(page: PageDef, links: EntryLinks): string {
  return renderToStaticMarkup(
    <Shell path={page.path} links={links}>
      {page.body}
    </Shell>,
  );
}

/** The page a path the website doesn't have answers with (404.html): not in the sitemap, and no part of the pages. */
export function notFoundPage(): PageDef {
  return {
    path: "/404/",
    title: `Page not found · ${brand.name}`,
    description: "This page isn't here.",
    body: (
      <section className={`${column} ${band}`}>
        <h1 className={heading}>Page not found</h1>
        <p className={`${muted} mt-4 max-w-prose`}>
          There is nothing at this address.{" "}
          <a href={LANDING} className={link}>
            Go to the home page
          </a>
          .
        </p>
      </section>
    ),
    islands: [],
  };
}
