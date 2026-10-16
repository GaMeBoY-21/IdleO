import { useFormContext } from "react-hook-form";
import { RadioGroup } from "@/components/ui/Inputs";
import { Tile } from "@/components/ui/Tile";
import type { BookValues } from "./form";
import { paramFields } from "./params";
import { ParamField, type TemplateParam } from "./ParamFields";
import { StepTitle } from "./StepTitle";

/** A basic parameter asked as a plain question ("3. Command"), its field generated from the catalog. */
export function StepParam({
  number,
  param,
  label,
  help,
}: {
  number: number;
  param: TemplateParam;
  label: string;
  help: string | null;
}) {
  const [field] = paramFields([param]);
  const id = `param-${param.name}`;
  return (
    <Tile as="section" aria-labelledby={id} className="flex flex-col gap-3">
      <StepTitle
        id={id}
        number={number}
        // One with a default takes it when left alone, so it isn't "optional"; one without is.
        optional={!param.required && param.defaultValue == null}
        help={help}
      >
        {label}
      </StepTitle>
      <ParamField field={field!} asked />
    </Tile>
  );
}

/** "GPU memory you need", for a template that can't tell from the renter's files: one of the GPU classes. */
export function StepGpu({
  number,
  label,
  help,
  choicesGb,
}: {
  number: number;
  label: string;
  help: string | null;
  choicesGb: number[];
}) {
  const form = useFormContext<BookValues>();
  return (
    <Tile as="section" aria-labelledby="gpu-memory" className="flex flex-col gap-3">
      <StepTitle id="gpu-memory" number={number} help={help}>
        {label}
      </StepTitle>
      <RadioGroup
        legend={label}
        hideLegend
        quiet
        options={choicesGb.map((gb) => ({ value: String(gb), label: `${gb} GB` }))}
        inputProps={() => form.register("gpuGb")}
      />
    </Tile>
  );
}
