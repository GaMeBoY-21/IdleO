import { lazy, Suspense, use, useEffect } from "react";
import { useLocation, useSearch } from "wouter";
import { useTemplates } from "@/api/queries";
import { Skeleton } from "@/components/ui/Skeleton";
import { Banner } from "@/components/ui/Banner";
import { ErrorState } from "@/components/ui/States";
import { Route } from "@/features/flow/Route";
import { YourSession } from "@/features/flow/YourSession";
import { afterFirstScreen, useAfterPaint } from "@/lib/after-first-screen";
import { bookHref, offeredServices, serviceOf, templatesOf } from "@/lib/catalog";
import { useLaptop } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { BookLayout } from "./BookLayout";
import { ServiceChoice, TemplateChoice } from "./WhatToRun";

const loadForm = () => import("./Prepared");
const Prepared = lazy(() => loadForm().then((module) => ({ default: module.Prepared })));
const QUESTION = "What do you want to run?";

/**
 * Book a GPU (web/DESIGN.md "Book", designed for a laptop first), one screen: what to run, your data, your GPU,
 * settings, and "Request a GPU". The choice is in the URL (`?service=fine-tune&template=qlora-finetune`), so back,
 * forward and reload keep it; a service with one template needs no template in it. Book again and Run again add
 * `&from=<session>` to start from an earlier setup. The form is a chunk of its own, fetched once the first screen
 * has loaded, so the services paint without it.
 */
export function Book() {
  useTitle("Book a GPU");
  const search = new URLSearchParams(useSearch());
  const [, navigate] = useLocation();
  const templates = useTemplates();
  const laptop = useLaptop();
  useEffect(() => afterFirstScreen(() => void loadForm()), []);

  const catalog = templates.data;
  const templateId = search.get("template");
  const serviceId =
    search.get("service") ?? (catalog && templateId ? serviceOf(catalog, templateId)?.id : null);
  const service = catalog?.services.find((s) => s.id === serviceId);
  const inService = catalog && service ? templatesOf(catalog, service.id) : [];
  const template = templateId === null ? inService[0] : inService.find((t) => t.id === templateId);
  // Once what to run has been painted, from the catalog, not just the skeleton before it.
  const painted = useAfterPaint(template !== undefined);

  const question = <h1 className={laptop ? "text-question" : "text-title"}>{QUESTION}</h1>;
  const services = catalog && (
    <ServiceChoice
      services={offeredServices(catalog)}
      templatesOf={(id) => templatesOf(catalog, id)}
      selected={service?.id ?? null}
      onPick={(id) => navigate(bookHref(id))}
    />
  );
  const generic = (
    <YourSession data={{ detail: "A file you upload, or a link", done: false }} runtime={null} />
  );

  if (!catalog || !service || !template) {
    const unknown = catalog !== undefined && (serviceId !== null || templateId !== null);
    return (
      <BookLayout
        laptop={laptop}
        back="/"
        // While the catalog loads, a link to a template keeps its summary's room, as the form will fill it.
        summary={!catalog && (serviceId !== null || templateId !== null) ? <SummarySkeleton /> : null}
        right={catalog ? generic : null}
        left={
          <>
            {question}
            {unknown && (
              <Banner tone="info">
                That isn&apos;t available any more. Choose what to run from the list.
              </Banner>
            )}
            {services ??
              (templates.isError ? (
                <ErrorState
                  error={templates.error}
                  what="what you can run"
                  onRetry={() => void templates.refetch()}
                  retrying={templates.isFetching}
                />
              ) : (
                <ChoicesSkeleton />
              ))}
          </>
        }
      />
    );
  }

  const choice = inService.length > 1 && (
    <TemplateChoice
      templates={inService}
      selected={template.id}
      onPick={(id) => navigate(bookHref(service.id, id))}
    />
  );
  const back = laptop ? "/" : "/book/";
  const fallback = (
    <BookLayout
      laptop={laptop}
      back={back}
      summary={<SummarySkeleton title={template.name} />}
      right={null}
      left={
        <>
          {/* What the form will show first, already here: the form's code arrives after (its own chunk). */}
          {laptop ? (
            question
          ) : (
            <h1 className="text-title">
              {template.input.accepts === "required" ? service.name : "Ready to run"}
            </h1>
          )}
          {laptop && services}
          {choice}
          <ChoicesSkeleton />
        </>
      }
    />
  );
  // The form's code (its own chunk) is asked for once the frame has painted, so the first paint, with what to run
  // in it, never waits on a second round of requests (web/README.md "The budgets"). Until then the frame is the
  // fallback of the same Suspense the form will use, so it's drawn once and stays put, not drawn again.
  return (
    <Suspense fallback={fallback}>
      {!painted ? (
        <Waiting />
      ) : (
        <Prepared
          key={template.id}
          template={template}
          from={search.get("from")}
          fallback={fallback}
          laptop={laptop}
          back={back}
          serviceName={service.name}
          choices={
            <>
              {laptop && services}
              {choice}
            </>
          }
        />
      )}
    </Suspense>
  );
}

/** Holds its Suspense on its fallback until the frame has painted (it never resolves; it's swapped for the form). */
const NEVER = new Promise<never>(() => undefined);
function Waiting(): never {
  return use(NEVER);
}

function ChoicesSkeleton() {
  return (
    <div role="status" aria-label="Loading" className="flex flex-col gap-2">
      {[0, 1, 2].map((key) => (
        <Skeleton key={key} className="h-18 w-full" />
      ))}
    </div>
  );
}

/**
 * The phone's route summary before the form arrives (BookRoute.tsx's BookSummary): the same route with the template's
 * name once it's known and its details still to come, so it takes exactly the room the summary will.
 */
function SummarySkeleton({ title }: { title?: string }) {
  const toCome = <span className="invisible">…</span>;
  return (
    <div aria-hidden="true" className="rounded-panel bg-ground px-4.5 py-4">
      <Route
        compact
        stops={[
          { title: title ?? toCome, detail: toCome, state: "next" },
          { title: "Your outputs", detail: toCome, state: "end" },
        ]}
      />
    </div>
  );
}
