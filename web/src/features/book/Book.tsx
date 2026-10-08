import { lazy, Suspense, useEffect } from "react";
import { Link, useLocation, useSearch } from "wouter";
import { useAvailability, useTemplates } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/States";
import { afterFirstScreen, useAfterPaint } from "@/lib/after-first-screen";
import { useTitle } from "@/lib/title";
import { FormSkeleton } from "./FormSkeleton";
import { PickStep } from "./PickStep";
import { StepNav, type Step } from "./StepNav";

const loadFlow = () => import("./BookFlow");
const Prepared = lazy(() => loadFlow().then((module) => ({ default: module.Prepared })));

/**
 * Book a GPU (product surface 3.2, designed for a laptop first): pick a template, configure it, review the quote and
 * book. The step and the template are in the URL (`?template=echo-input&step=configure`), so back and forward work
 * and a reload keeps the step; what's typed is kept per template in this tab until it's booked. Run again opens
 * configure filled from an earlier session (`&from=<id>`).
 */
export function Book() {
  useTitle("Book a GPU");
  const search = new URLSearchParams(useSearch());
  const [, navigate] = useLocation();
  const templates = useTemplates();
  // The price hints come just after the gallery paints.
  const availability = useAvailability(useAfterPaint());
  const templateId = search.get("template");
  const step: Step = templateId === null ? "pick" : search.get("step") === "review" ? "review" : "configure";
  const template = templates.data?.templates.find((t) => t.id === templateId);
  useEffect(() => afterFirstScreen(() => void loadFlow()), []);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3">
        <h1 className="text-heading font-semibold">Book a GPU</h1>
        <StepNav current={step} templateId={templateId} />
      </div>
      {step === "pick" ? (
        <PickStep
          templates={templates}
          availability={availability}
          onPick={(id) => navigate(`/book/?template=${encodeURIComponent(id)}&step=configure`)}
        />
      ) : templates.data && !template ? (
        <Card>
          <EmptyState
            title="That template isn't available"
            action={
              <Link href="/book/" className={buttonClasses("primary")}>
                Pick a template
              </Link>
            }
          >
            It may have been replaced. Pick one from the list.
          </EmptyState>
        </Card>
      ) : template && (availability.data || availability.isError) ? (
        <Suspense fallback={<FormSkeleton />}>
          <Prepared
            template={template}
            from={search.get("from")}
            step={step}
            availability={availability.data}
          />
        </Suspense>
      ) : (
        <FormSkeleton />
      )}
    </div>
  );
}
