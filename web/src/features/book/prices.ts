import type { components } from "@/api/schema.gen";
import type { Template } from "@/api/queries";

type Availability = components["schemas"]["Availability"];

/** The GPU memory a booking may ask for: the template's least, or more, as availability shows. */
export function memoryChoices(template: Template, availability: Availability | undefined): number[] {
  const sizes = new Set([template.minVramMb]);
  for (const gpu of availability?.gpus ?? []) {
    if (gpu.vramMb > template.minVramMb) {
      sizes.add(gpu.vramMb);
    }
  }
  return [...sizes].sort((a, b) => a - b);
}
