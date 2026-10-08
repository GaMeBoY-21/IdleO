import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { FormProvider, useForm, useWatch } from "react-hook-form";
import { Link, useLocation, useSearch } from "wouter";
import { api, data } from "@/api/client";
import type { FieldError } from "@/api/errors";
import { keys, useAvailability, useSession, useTemplates, writeSession, type Template } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState } from "@/components/ui/States";
import { useLaptop } from "@/lib/media";
import { useTitle } from "@/lib/title";
import { ConfigureStep } from "./ConfigureStep";
import {
  bookSchema,
  defaultValues,
  forgetDraft,
  readDraft,
  saveDraft,
  toRequest,
  valuesFrom,
  type BookValues,
} from "./form";
import { fieldOf } from "./params";
import { PickStep } from "./PickStep";
import { ReviewStep } from "./ReviewStep";
import { StepNav, type Step } from "./StepNav";
import { readyFile, useUpload, type ChosenFile } from "./useUpload";

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
  const availability = useAvailability();
  const templateId = search.get("template");
  const step: Step = templateId === null ? "pick" : search.get("step") === "review" ? "review" : "configure";
  const template = templates.data?.templates.find((t) => t.id === templateId);

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
        <Prepared
          template={template}
          from={search.get("from")}
          step={step}
          availability={availability.data}
        />
      ) : (
        <FormSkeleton />
      )}
    </div>
  );
}

/** Works out what the form starts with: an earlier session (Run again), the draft, or the template's defaults. */
function Prepared({
  template,
  from,
  step,
  availability,
}: {
  template: Template;
  from: string | null;
  step: Exclude<Step, "pick">;
  availability: Parameters<typeof defaultValues>[1];
}) {
  const earlier = useSession(from);
  const draft = useMemo(() => (from === null ? readDraft(template.id) : null), [from, template.id]);
  const source = from !== null ? (earlier.data?.templateId === template.id ? earlier.data : null) : null;
  const uploadId = source ? (source.input?.uploadId ?? null) : (draft?.upload?.id ?? null);
  // A kept upload is used again only while it's still ready.
  const upload = useQuery({
    queryKey: ["upload", uploadId],
    enabled: uploadId !== null,
    queryFn: () => data(api.GET("/v1/uploads/{id}", { params: { path: { id: uploadId! } } })),
    retry: false,
  });

  const waiting = (from !== null && earlier.isPending) || (uploadId !== null && upload.isPending);
  if (waiting) {
    return <FormSkeleton />;
  }
  const values = source
    ? valuesFrom(template, source)
    : (draft?.values ?? defaultValues(template, availability));
  const file =
    upload.data?.status === "ready"
      ? readyFile(upload.data.id, upload.data.filename, upload.data.sizeBytes)
      : null;
  return (
    <Flow
      key={template.id}
      template={template}
      step={step}
      initial={values}
      initialFile={file}
      availability={availability}
    />
  );
}

function Flow({
  template,
  step,
  initial,
  initialFile,
  availability,
}: {
  template: Template;
  step: Exclude<Step, "pick">;
  initial: BookValues;
  initialFile: ChosenFile | null;
  availability: Parameters<typeof defaultValues>[1];
}) {
  const [, navigate] = useLocation();
  const client = useQueryClient();
  const laptop = useLaptop();
  const form = useForm<BookValues>({
    resolver: zodResolver(bookSchema(template)),
    defaultValues: initial,
    mode: "onTouched",
  });
  const { file, choose, remove } = useUpload(initialFile);

  const values = useWatch({ control: form.control }) as BookValues;
  const uploadId = file?.progress.uploadId ?? null;
  const at = (to: Step, replace = false) =>
    navigate(`/book/?template=${encodeURIComponent(template.id)}&step=${to}`, { replace });

  // The draft: what's typed, and the upload once it's ready.
  useEffect(() => {
    saveDraft(template.id, {
      values,
      upload:
        file?.progress.phase === "ready" && uploadId
          ? { id: uploadId, name: file.name, size: file.size }
          : null,
    });
  }, [template.id, values, file, uploadId]);

  const needsInput = template.input.accepts === "required";
  // Opened at review without its input (a reload loses an upload in flight): configure says what's missing.
  const [inputMissing, setInputMissing] = useState(
    () => step === "review" && needsInput && initial.inputKind === "upload" && initialFile === null,
  );
  const hasInput =
    values.inputKind === "upload"
      ? file !== null && file.progress.phase !== "failed"
      : values.inputUrl.trim() !== "";

  // Review opened with something missing (a reload lost an upload in flight): back to configure, which says what.
  const valid = bookSchema(template).safeParse(values).success && (!needsInput || hasInput);
  useEffect(() => {
    if (step === "review" && !valid) {
      at("configure", true);
      void form.trigger();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only when the step or validity changes
  }, [step, valid]);

  const review = () =>
    void form.handleSubmit(
      () => {
        setInputMissing(needsInput && !hasInput);
        if (!needsInput || hasInput) {
          at("review");
        }
      },
      () => setInputMissing(needsInput && !hasInput),
    )();

  const refused = (errors: FieldError[]) => {
    for (const error of errors) {
      const field = fieldOf(error.field);
      if (field) {
        const detail = error.detail.charAt(0).toUpperCase() + error.detail.slice(1);
        form.setError(field as keyof BookValues, { message: detail.endsWith(".") ? detail : `${detail}.` });
      }
    }
    at("configure");
  };

  return (
    <FormProvider {...form}>
      {step === "configure" || !valid ? (
        <ConfigureStep
          template={template}
          availability={availability}
          file={file}
          onChoose={(chosen) => {
            setInputMissing(false);
            choose(chosen);
          }}
          onRemove={() => void remove()}
          inputMissing={inputMissing}
          onReview={review}
          laptop={laptop}
        />
      ) : (
        <ReviewStep
          template={template}
          values={values}
          request={toRequest(template, values, uploadId)}
          file={file}
          laptop={laptop}
          onBooked={(session) => {
            forgetDraft(template.id);
            writeSession(client, session);
            void client.invalidateQueries({ queryKey: keys.sessions });
            void client.invalidateQueries({ queryKey: keys.wallet });
            navigate(`/session/?id=${session.id}`);
          }}
          onRefused={refused}
        />
      )}
    </FormProvider>
  );
}

function FormSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading the form"
      className="rounded-card border border-border bg-surface p-4 md:p-6"
    >
      <SkeletonLine size="title" width="w-1/4" />
      <div className="mt-4 flex flex-col gap-5">
        {[0, 1, 2].map((key) => (
          <div key={key}>
            <SkeletonLine size="small" width="w-1/4" />
            <div className="mt-2 h-touch rounded-control bg-sunken" />
          </div>
        ))}
      </div>
    </div>
  );
}
