import { useFormContext, useWatch } from "react-hook-form";
import type { components } from "@/api/schema.gen";
import type { Template } from "@/api/queries";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Field } from "@/components/ui/Field";
import { Select } from "@/components/ui/Inputs";
import { Money } from "@/components/ui/Money";
import { PRICE_STEP, paisePerMinute } from "@/lib/money";
import { gigabytes, hoursAndMinutes, memoryChoices, type BookValues } from "./form";
import { InputSection } from "./InputSection";
import { ParamFields } from "./ParamFields";
import type { ChosenFile } from "./useUpload";

type Availability = components["schemas"]["Availability"];

/**
 * Step 2: the template's parameters, its input, and the limits: GPU memory, the most per hour, the spending cap and
 * the longest it may run. Designed for a laptop: the form on the left, what's chosen and the next step on the right.
 */
export function ConfigureStep({
  template,
  availability,
  file,
  onChoose,
  onRemove,
  inputMissing,
  onReview,
  laptop,
}: {
  template: Template;
  availability: Availability | undefined;
  file: ChosenFile | null;
  onChoose: (file: File) => void;
  onRemove: () => void;
  inputMissing: boolean;
  onReview: () => void;
  laptop: boolean;
}) {
  const form = useFormContext<BookValues>();
  const errors = form.formState.errors;
  const perHour = useWatch({ control: form.control, name: "maxPerHour" });
  const perMinute = paisePerMinute(perHour);
  const next = (
    <Button type="submit" block={!laptop}>
      Review
    </Button>
  );

  const fields = (
    <div className="flex min-w-0 flex-col gap-8">
      {template.params.length > 0 && (
        <section aria-labelledby="settings-title" className="flex flex-col gap-4">
          <h2 id="settings-title" className="text-title font-semibold">
            Settings
          </h2>
          <ParamFields params={template.params} />
          <p className="max-w-prose text-caption text-text-muted">
            Don&apos;t put passwords or tokens here. Parameters are visible to the job and kept with the
            session.
          </p>
        </section>
      )}
      {template.input.accepts !== "none" && (
        <InputSection
          template={template}
          file={file}
          onChoose={onChoose}
          onRemove={onRemove}
          missing={inputMissing}
        />
      )}
      <section aria-labelledby="limits-title" className="flex flex-col gap-4">
        <h2 id="limits-title" className="text-title font-semibold">
          Limits
        </h2>
        <div className="grid gap-x-6 gap-y-5 md:grid-cols-2">
          <Select label="GPU memory" error={errors.vramMb?.message} {...form.register("vramMb")}>
            {memoryChoices(template, availability).map((mb) => (
              <option key={mb} value={String(mb)}>
                At least {gigabytes(mb)}
              </option>
            ))}
          </Select>
          <Field
            label="The most per hour, in ₹"
            type="number"
            inputMode="decimal"
            step={PRICE_STEP}
            min={PRICE_STEP}
            error={errors.maxPerHour?.message}
            hint={
              <>
                You pay the host&apos;s price, never more than this
                {perMinute !== null && perMinute > 0 && (
                  <>
                    {" "}
                    (<Money paise={perMinute} /> a minute)
                  </>
                )}
                .
              </>
            }
            {...form.register("maxPerHour")}
          />
          <Field
            label="Spending cap, in ₹"
            type="number"
            inputMode="decimal"
            step="0.01"
            min="0.01"
            hint="The session stops, after a last checkpoint, if it costs this much."
            error={errors.spendingCap?.message}
            {...form.register("spendingCap")}
          />
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-small font-medium">Longest it may run</legend>
            <div className="grid grid-cols-2 gap-3">
              <Field
                label="Hours"
                type="number"
                inputMode="numeric"
                min="0"
                step="1"
                aria-invalid={errors.runtimeHours ? true : undefined}
                {...form.register("runtimeHours")}
              />
              <Field
                label="Minutes"
                type="number"
                inputMode="numeric"
                min="0"
                max="59"
                step="1"
                aria-invalid={errors.runtimeMinutes ? true : undefined}
                {...form.register("runtimeMinutes")}
              />
            </div>
            <p
              className={
                errors.runtimeHours || errors.runtimeMinutes
                  ? "text-caption text-danger"
                  : "text-caption text-text-muted"
              }
            >
              {errors.runtimeHours?.message ??
                errors.runtimeMinutes?.message ??
                `Up to ${hoursAndMinutes(template.maxRuntimeCapSeconds)} for this template.`}
            </p>
          </fieldset>
        </div>
      </section>
    </div>
  );

  return (
    <form
      noValidate
      aria-label={`Configure ${template.name}`}
      onSubmit={(event) => {
        event.preventDefault();
        onReview();
      }}
    >
      {laptop ? (
        <div className="grid grid-cols-12 items-start gap-6">
          <Card className="col-span-8">{fields}</Card>
          <Card className="sticky top-16 col-span-4 flex flex-col gap-4">
            <p className="text-small text-text-muted">Template</p>
            <p className="text-body font-medium break-words">{template.name}</p>
            <p className="text-small text-text-muted">
              Next, you&apos;ll see the exact hold and price before booking.
            </p>
            {next}
          </Card>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <Card>{fields}</Card>
          <p className="text-small text-text-muted">
            Next, you&apos;ll see the exact hold and price before booking.
          </p>
          {next}
        </div>
      )}
    </form>
  );
}
