import { lazy, Suspense, use, useEffect } from "react";
import { useLocation, useSearch } from "wouter";
import { useTemplates } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { PageHeading } from "@/components/ui/Heading";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { YourSession } from "@/features/flow/YourSession";
import { afterFirstScreen, useAfterPaint } from "@/lib/after-first-screen";
import { bookHref, offeredServices, serviceOf, templatesOf } from "@/lib/catalog";
import { useLaptop } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { BookLayout } from "./BookLayout";
import { ServiceHeader } from "./ServiceHeader";
import { ServiceChoice, TemplateChoice } from "./WhatToRun";

const loadForm = () => import("./Prepared");
const Prepared = lazy(() => loadForm().then((module) => ({ default: module.Prepared })));
const QUESTION = "What do you want to run?";

/**
 * Book a GPU (web/DESIGN.md "Book", designed for a laptop first). Without a service, the services to choose from;
 * with one (`?service=fine-tune`), that service's own screen, built from its template in the catalog
 * (docs/design/redesign/book-finetune-laptop.html). The choice is in the URL, so back, forward and reload keep it; a
 * service with one template needs no `&template=`. Book again and Run again add `&from=<session>` to start from an
 * earlier setup. The form is a chunk of its own, fetched once the first screen has loaded, so the screen paints
 * without it.
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
  // Once the service's screen has been painted, from the catalog, not just the skeleton before it.
  const painted = useAfterPaint(template !== undefined);

  if (!catalog || !service || !template) {
    const unknown = catalog !== undefined && (serviceId !== null || templateId !== null);
    return (
      <BookLayout
        laptop={laptop}
        right={
          catalog ? (
            <YourSession data={{ detail: "Your model, data or code", done: false }} runtime={null} />
          ) : null
        }
        left={
          <>
            <PageHeading>{QUESTION}</PageHeading>
            {unknown && (
              <Banner tone="info">
                That isn&apos;t available any more. Choose what to run from the list.
              </Banner>
            )}
            {catalog ? (
              <ServiceChoice
                services={offeredServices(catalog)}
                templatesOf={(id) => templatesOf(catalog, id)}
                selected={null}
                onPick={(id) => navigate(bookHref(id))}
              />
            ) : templates.isError ? (
              <ErrorState
                error={templates.error}
                what="what you can run"
                onRetry={() => void templates.refetch()}
                retrying={templates.isFetching}
              />
            ) : (
              <StepsSkeleton count={3} />
            )}
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
  const fallback = (
    <BookLayout
      laptop={laptop}
      service
      right={null}
      left={
        <>
          {/* What the form will show first, already here: the form's code arrives after (its own chunk). */}
          <ServiceHeader name={service.name} description={template.description} />
          {choice}
          <StepsSkeleton count={template.inputs.length + template.questions.length} />
        </>
      }
    />
  );
  // The form's code (its own chunk) is asked for once the frame has painted, so the first paint never waits on a
  // second round of requests (web/README.md "The budgets"). Until then the frame is the fallback of the same
  // Suspense the form will use, so it's drawn once and stays put, not drawn again.
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
          serviceName={service.name}
          choice={choice}
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

function StepsSkeleton({ count }: { count: number }) {
  return (
    <div role="status" aria-label="Loading" className="flex flex-col gap-2.5 md:gap-3.5">
      {Array.from({ length: Math.max(1, count) }, (_, key) => (
        <Skeleton key={key} tile className="h-24 w-full" />
      ))}
    </div>
  );
}
