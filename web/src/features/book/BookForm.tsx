import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { SlidersHorizontal } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { FormProvider, useForm, useWatch, type FieldErrors } from "react-hook-form";
import { useLocation } from "wouter";
import { ApiError, type FieldError } from "@/api/errors";
import { useAvailability, type Template } from "@/api/queries";
import { RowButton } from "@/components/ui/Option";
import { useAfterPaint } from "@/lib/after-first-screen";
import { BookLayout } from "./BookLayout";
import { BookPanel, BookSummary } from "./BookRoute";
import { bookSchema, forgetDraft, saveDraft, toRequest, type BookValues } from "./form";
import { GpuCard } from "./GpuCard";
import { InputSection } from "./InputSection";
import { fieldOf } from "./params";
import { RequestButton } from "./RequestButton";
import { SettingsSheet } from "./SettingsSheet";
import { inSheet, missingParams, settingsSummary } from "./summary";
import { useQuote } from "./useQuote";
import { useRequest } from "./useRequest";
import { useUpload, type ChosenFile } from "./useUpload";
import { firstInput } from "@/lib/inputs";

export interface BookFormProps {
  template: Template;
  initial: BookValues;
  initialFile: ChosenFile | null;
  laptop: boolean;
  /** What to run: the services and the template choice on a laptop; on a phone, only the template choice. */
  choices: ReactNode;
  /** The service's name: the phone's heading until the data is given, then "Ready to run". */
  serviceName: string;
  back: string;
}

/** The fields a form check found wrong, as their names: `params.greeting`, `spendingCap`. */
const fieldsOf = (errors: FieldErrors<BookValues>): string[] =>
  Object.keys(errors).flatMap((key) =>
    key === "params" ? Object.keys(errors.params ?? {}).map((name) => `params.${name}`) : [key],
  );

const asSentence = (detail: string) => {
  const text = detail.charAt(0).toUpperCase() + detail.slice(1);
  return text.endsWith(".") ? text : `${text}.`;
};

/**
 * Book, one screen (web/DESIGN.md "Book"): what to run, your data, your GPU, the settings in a row that opens a
 * sheet, and "Request a GPU". There's no GPU or price to choose: the quote, without a ceiling unless the renter set
 * one, shows what fits now and what's held. What's typed is kept per template in this tab until it's requested.
 */
export function BookForm({
  template,
  initial,
  initialFile,
  laptop,
  choices,
  back,
  serviceName,
}: BookFormProps) {
  const [, navigate] = useLocation();
  const client = useQueryClient();
  const form = useForm<BookValues>({
    resolver: zodResolver(bookSchema(template)),
    defaultValues: initial,
    mode: "onTouched",
  });
  const { file, choose, remove } = useUpload(initialFile);
  const values = useWatch({ control: form.control }) as BookValues;
  const painted = useAfterPaint();
  const availability = useAvailability(painted);
  const [sheet, setSheet] = useState<{ open: boolean; focus: string | null }>({ open: false, focus: null });
  const [inputMissing, setInputMissing] = useState(false);

  const ready = file?.progress.phase === "ready" ? file.progress.uploadId : null;
  useEffect(() => {
    saveDraft(template.id, {
      values,
      upload: ready && file ? { id: ready, name: file.name, size: file.size } : null,
    });
  }, [template.id, values, file, ready]);

  const request = toRequest(template, values, ready);
  // Settings the form can see are wrong (a required parameter left empty) aren't quoted: the row says what's needed.
  const quotable = bookSchema(template).safeParse(values).success;
  const quote = useQuote(quotable ? request : null, painted);
  const book = useRequest(request, (session) => {
    forgetDraft(template.id);
    navigate(`/session/?id=${session.id}`);
  });

  const needsInput = firstInput(template)?.required === true;
  const hasInput =
    values.inputKind === "upload"
      ? file !== null && file.progress.phase !== "failed"
      : values.inputUrl.trim() !== "";
  const uploading =
    file && file.progress.phase !== "ready" && file.progress.phase !== "failed"
      ? file.progress.percent
      : null;

  /** Puts the server's words on each field it refused; returns those fields, and whether it refused the input. */
  const markRefused = (errors: FieldError[]) => {
    const fields: string[] = [];
    for (const error of errors) {
      const field = fieldOf(error.field);
      if (field) {
        form.setError(field as keyof BookValues, { message: asSentence(error.detail) });
        fields.push(field);
      }
    }
    return { fields, input: errors.some((error) => error.field.startsWith("input")) };
  };

  // A quote the server refuses says why on the fields, without opening anything.
  const quoteError = quote.kind === "error" ? quote.error : null;
  useEffect(() => {
    if (quoteError instanceof ApiError && quoteError.type === "/problems/validation-failed") {
      markRefused(quoteError.errors);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per refusal
  }, [quoteError]);

  const onRequest = () =>
    void form.handleSubmit(
      () => {
        if (needsInput && !hasInput) {
          setInputMissing(true);
          return;
        }
        book.mutate(undefined, {
          onError: (error) => {
            if (error instanceof ApiError && error.type === "/problems/validation-failed") {
              // A request the server refuses opens Settings at the first refused field there.
              const { fields, input } = markRefused(error.errors);
              setInputMissing(input);
              const first = fields.find(inSheet);
              if (first) {
                setSheet({ open: true, focus: first });
              }
            } else {
              void client.invalidateQueries({ queryKey: ["quote"] });
            }
          },
        });
      },
      (errors) => {
        setInputMissing(needsInput && !hasInput);
        const first = fieldsOf(errors).find(inSheet);
        if (first) {
          setSheet({ open: true, focus: first });
        }
      },
    )();

  const missing = missingParams(template, values);
  const left = (
    <>
      {laptop ? (
        <h1 className="text-question">What do you want to run?</h1>
      ) : (
        <h1 className="text-title">{needsInput && !hasInput ? serviceName : "Ready to run"}</h1>
      )}
      {choices}
      {firstInput(template) !== null && (
        <InputSection
          template={template}
          file={file}
          onChoose={(chosen) => {
            setInputMissing(false);
            choose(chosen);
          }}
          onRemove={() => void remove()}
          missing={inputMissing}
          laptop={laptop}
        />
      )}
      <section aria-labelledby="gpu-title" className="flex flex-col gap-3">
        <h2 id="gpu-title" className={laptop ? "text-section font-bold" : "sr-only"}>
          Your GPU
        </h2>
        <GpuCard
          quote={quotable ? quote : null}
          vramMb={Number(values.vramMb)}
          typicalSeconds={template.typicalRuntimeSeconds}
          laptop={laptop}
        />
      </section>
      <RowButton
        icon={SlidersHorizontal}
        title="Settings"
        detail={
          missing.length > 0 ? `Needs: ${missing.join(", ")}` : settingsSummary(template, values, !laptop)
        }
        onClick={() => setSheet({ open: true, focus: null })}
      />
      <div className="mt-auto pt-2">
        <RequestButton
          quote={quotable ? quote : null}
          uploadingPercent={uploading}
          busy={book.isPending}
          error={book.error}
          onRequest={onRequest}
          laptop={laptop}
        />
      </div>
      <SettingsSheet
        template={template}
        availability={availability.data}
        open={sheet.open}
        onOpenChange={(open) => setSheet({ open, focus: null })}
        focus={sheet.focus}
        laptop={laptop}
      />
    </>
  );

  return (
    <FormProvider {...form}>
      <BookLayout
        laptop={laptop}
        back={back}
        left={left}
        right={<BookPanel template={template} values={values} file={file} quote={quote} />}
        summary={<BookSummary template={template} values={values} file={file} />}
      />
    </FormProvider>
  );
}
