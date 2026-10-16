import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { FormProvider, useForm, useWatch, type FieldErrors } from "react-hook-form";
import { useLocation } from "wouter";
import { ApiError, type FieldError } from "@/api/errors";
import { keys, type Template } from "@/api/queries";
import { useAfterPaint } from "@/lib/after-first-screen";
import { Advanced } from "./Advanced";
import { BookLayout } from "./BookLayout";
import { Budget } from "./Budget";
import { forgetDraft, keptUploads, saveDraft } from "./draft";
import { bookSchema, inputGiven, modelBytes, toRequest, type BookValues } from "./form";
import { GpuCard, memoryNeeded, tooBig } from "./GpuCard";
import { ModelLine } from "./ModelLine";
import { fieldOf } from "./params";
import { RequestButton } from "./RequestButton";
import { ServiceHeader } from "./ServiceHeader";
import { StepInput } from "./StepInput";
import { StepPreset } from "./StepPreset";
import { StepGpu, StepParam } from "./StepQuestion";
import { advancedParams, chosenOption, stepsOf } from "./steps";
import { useQuote } from "./useQuote";
import { useRequest } from "./useRequest";
import { isBusy, useUploads, type InputUpload } from "./useUploads";
import { BIGGEST_MB, neededMb } from "./vram";
import { YourRequest } from "./YourRequest";

export interface ServiceFormProps {
  template: Template;
  serviceName: string;
  initial: BookValues;
  initialUploads: Record<string, InputUpload | null>;
  laptop: boolean;
  /** Which template, for a service with more than one. */
  choice: ReactNode;
}

const asSentence = (detail: string) => {
  const text = detail.charAt(0).toUpperCase() + detail.slice(1);
  return text.endsWith(".") ? text : `${text}.`;
};

/** Whether a form field lives behind Advanced. */
const inAdvanced = (template: Template, field: string) =>
  ["maxPerHour", "runtimeHours", "runtimeMinutes", "vramMb"].includes(field) ||
  advancedParams(template).some((param) => field === `params.${param.name}`);

/** The fields a form check found wrong, as their names: `params.epochs`, `budget`. */
const fieldsOf = (errors: FieldErrors<BookValues>): string[] =>
  Object.keys(errors).flatMap((key) =>
    key === "params" ? Object.keys(errors.params ?? {}).map((name) => `params.${name}`) : [key],
  );

/**
 * A service's booking screen (docs/design/redesign/book-finetune-laptop.html, ADR 0022), built from its template in
 * the catalog: numbered questions (its inputs, then its plain questions), the budget, Advanced, and "Your request"
 * with the GPU this needs, its price and "Request a GPU". There's no GPU or price to choose: the quote shows what
 * fits now and what's held. What's answered is kept per template in this tab until it's requested.
 */
export function ServiceForm({
  template,
  serviceName,
  initial,
  initialUploads,
  laptop,
  choice,
}: ServiceFormProps) {
  const [, navigate] = useLocation();
  const client = useQueryClient();
  // Made once per template: every render checks the answers against it.
  const schema = useMemo(() => bookSchema(template), [template]);
  const form = useForm<BookValues>({
    resolver: zodResolver(schema),
    defaultValues: initial,
    mode: "onTouched",
  });
  const { uploads, choose, retry, pick, remove } = useUploads(initialUploads);
  const values = useWatch({ control: form.control }) as BookValues;
  const painted = useAfterPaint();
  const [advanced, setAdvanced] = useState(false);
  const [missing, setMissing] = useState<string[]>([]);

  useEffect(
    () => saveDraft(template.id, { values, uploads: keptUploads(uploads) }),
    [template.id, values, uploads],
  );
  // A finished upload is in My files now.
  const ready = Object.values(uploads)
    .map((upload) => (upload?.progress.phase === "ready" ? upload.progress.uploadId : null))
    .join();
  useEffect(() => void client.invalidateQueries({ queryKey: keys.uploads }), [client, ready]);

  const request = toRequest(template, values, uploads);
  // Answers the form can see are wrong aren't quoted: the questions say what's needed.
  const quotable = schema.safeParse(values).success;
  const quote = useQuote(quotable ? request : null, painted);
  const book = useRequest(request, (session) => {
    forgetDraft(template.id);
    navigate(`/session/?id=${session.id}`);
  });
  const vram = request.vramNeededMb ?? template.minVramMb;
  const big = tooBig(quote, vram);
  const going = Object.values(uploads).filter((upload) => upload !== null && isBusy(upload));
  const total = going.reduce((sum, upload) => sum + upload!.size, 0);
  const uploading =
    going.length === 0
      ? null
      : Math.min(
          99,
          Math.floor((going.reduce((sum, u) => sum + u!.progress.sentBytes, 0) / Math.max(1, total)) * 100),
        );
  const preset = template.questions.find((question) => question.kind === "preset");
  const minutes = preset?.name
    ? chosenOption(template, preset.name, values.presets[preset.name])?.minutes
    : null;
  const typicalMinutes =
    minutes ?? (template.typicalRuntimeSeconds == null ? null : template.typicalRuntimeSeconds / 60);

  /** Puts the server's words on each field it refused. */
  const markRefused = (errors: FieldError[]) => {
    for (const error of errors) {
      const field = fieldOf(error.field);
      if (field) {
        form.setError(field as keyof BookValues, { message: asSentence(error.detail) });
      }
    }
  };
  // Advanced opens by itself while something in it needs fixing.
  const advancedOpen =
    advanced || fieldsOf(form.formState.errors).some((field) => inAdvanced(template, field));
  const quoteError = quote.kind === "error" ? quote.error : null;
  useEffect(() => {
    if (quoteError instanceof ApiError && quoteError.type === "/problems/validation-failed") {
      markRefused(quoteError.errors);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per refusal
  }, [quoteError]);

  const needed = template.inputs
    .filter((input) => input.required && !inputGiven(values, uploads, input.name))
    .map((input) => input.name);
  const pointAt = (names: string[]) => {
    setMissing(names);
    document.getElementById(`input-${names[0] ?? ""}`)?.scrollIntoView({ block: "center" });
  };
  const onRequest = () =>
    void form.handleSubmit(
      () => {
        if (needed.length > 0) {
          pointAt(needed);
          return;
        }
        book.mutate(undefined, {
          onError: (error) => {
            if (error instanceof ApiError && error.errors.length > 0) {
              markRefused(error.errors);
            } else {
              void client.invalidateQueries({ queryKey: ["quote"] });
            }
          },
        });
      },
      () => {
        if (needed.length > 0) {
          pointAt(needed);
        }
      },
    )();

  const gpu = (
    <div className="flex flex-col gap-2">
      <GpuCard
        quote={quotable ? quote : null}
        vramMb={vram}
        typicalMinutes={typicalMinutes}
        laptop={laptop}
      />
      {memoryNeeded(quote, vram) <= BIGGEST_MB && (
        <ModelLine template={template} values={values} uploads={uploads} />
      )}
    </div>
  );
  const button = (
    <RequestButton
      quote={quotable ? quote : null}
      uploadingPercent={uploading}
      tooBig={big}
      busy={book.isPending}
      error={book.error}
      onRequest={onRequest}
      laptop={laptop}
    />
  );
  const yourRequest = (
    <YourRequest
      template={template}
      values={values}
      uploads={uploads}
      gpu={gpu}
      button={button}
      laptop={laptop}
    />
  );
  const left = (
    <>
      <ServiceHeader name={serviceName} description={template.description} laptop={laptop} />
      {choice}
      {stepsOf(template).map((step) => {
        switch (step.kind) {
          case "input":
            return (
              <StepInput
                key={step.input.name}
                number={step.number}
                input={step.input}
                upload={uploads[step.input.name] ?? null}
                missing={missing.includes(step.input.name) && needed.includes(step.input.name)}
                onChoose={(chosen) => choose(step.input.name, chosen)}
                onPick={(picked) => pick(step.input.name, picked)}
                onRemove={() => void remove(step.input.name)}
                onRetry={() => retry(step.input.name)}
              />
            );
          case "preset":
            return <StepPreset key={step.name} {...step} quote={quotable ? quote : null} />;
          case "param":
            return <StepParam key={step.param.name} {...step} />;
          case "gpu":
            return <StepGpu key="gpu" {...step} />;
        }
      })}
      <Budget chips={template.budgetChips} />
      <Advanced
        template={template}
        neededMb={neededMb(template, modelBytes(template, values, uploads))}
        open={advancedOpen}
        onToggle={() => setAdvanced(!advancedOpen)}
      />
      {!laptop && yourRequest}
    </>
  );

  return (
    <FormProvider {...form}>
      <BookLayout laptop={laptop} service left={left} right={laptop ? yourRequest : null} />
    </FormProvider>
  );
}
