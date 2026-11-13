import type { DocPage } from "../context";
import { column, heading, link, readable } from "../ui";

/**
 * A docs page (ADR 0029): the docs' own navigation, then the page's body, written in Markdown (docs/site/) or generated
 * from the catalog, the command line and the API contract (scripts/site/), and rendered to HTML when the site is built.
 */
export function Docs({ page, pages }: { page: DocPage; pages: DocPage[] }) {
  return (
    <div className={`${column} ${readable} py-8 md:py-12 xl:flex xl:gap-12`}>
      <nav aria-label="Docs" className="mb-8 xl:mb-0 xl:w-menu xl:shrink-0">
        <p className="mb-2 text-eyebrow text-ink-subtle">Docs</p>
        <ul className="flex flex-wrap gap-2 xl:flex-col xl:gap-1">
          {pages.map((item) => (
            <li key={item.path}>
              <a
                href={item.path}
                aria-current={item.path === page.path ? "page" : undefined}
                className="flex min-h-control items-center rounded-control px-2 text-button text-ink-subtle hover:bg-surface-2 hover:text-ink pointer-coarse:min-h-touch aria-[current=page]:bg-surface-3 aria-[current=page]:text-ink"
              >
                {item.title}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <article className="docs min-w-0 xl:flex-1">
        <h1 className={`${heading} mb-6`}>{page.title}</h1>
        <div dangerouslySetInnerHTML={{ __html: page.html }} />
        <p className="mt-10 text-small text-ink-subtle">
          Something wrong or missing?{" "}
          <a href="/contact/" className={link}>
            Contact
          </a>
        </p>
      </article>
    </div>
  );
}
