import { formatPaise, formatPaiseRange } from "./money";
import type { components } from "@/api/schema.gen";

type Service = components["schemas"]["ServiceResponse"];
type Template = components["schemas"]["TemplateResponse"];
type Catalog = { services: Service[]; templates: Template[] };

/**
 * The services a renter is offered (web/DESIGN.md "Voice": a service is something you can run; a template is one
 * way to run it): those that aren't hidden and have at least one template, in the catalog's order.
 */
export const offeredServices = (catalog: Catalog): Service[] =>
  catalog.services.filter(
    (service) => !service.hidden && catalog.templates.some((template) => template.service === service.id),
  );

/** A service's templates, in the catalog's order. */
export const templatesOf = (catalog: Catalog, serviceId: string): Template[] =>
  catalog.templates.filter((template) => template.service === serviceId);

/** The service a template belongs to; undefined for a template the catalog no longer has. */
export const serviceOf = (catalog: Catalog, templateId: string): Service | undefined => {
  const template = catalog.templates.find((t) => t.id === templateId);
  return template && catalog.services.find((service) => service.id === template.service);
};

/** A tagline as a line of its own: "A model on your data". */
export const sentence = (tagline: string) => tagline.charAt(0).toUpperCase() + tagline.slice(1);

/** "about 45 min", "about 20 s", "about 2 h": a template's typical runtime; null when it depends on the data. */
export function aboutRuntime(seconds: number | null | undefined): string | null {
  if (seconds === null || seconds === undefined) {
    return null;
  }
  if (seconds < 90) {
    return `about ${Math.max(10, Math.round(seconds / 10) * 10)} s`;
  }
  if (seconds < 90 * 60) {
    return `about ${Math.round(seconds / 60)} min`;
  }
  return `about ${Math.round(seconds / 3600)} h`;
}

/** Book, at a service and, when it's known, one of its templates (web/DESIGN.md "Book"). */
export function bookHref(serviceId: string, templateId?: string): string {
  const query = new URLSearchParams({ service: serviceId });
  if (templateId !== undefined) {
    query.set("template", templateId);
  }
  return `/book/?${query.toString()}`;
}

/**
 * A service's typical time, from its templates in the catalog: the shortest to the longest of its presets' minutes
 * ("about 15–75 min"), else the template's own typical runtime, else that the renter sets the time limit.
 */
export function serviceTime(catalog: Catalog, serviceId: string): string {
  const minutes = serviceMinutes(catalog, serviceId);
  if (minutes && minutes.from !== "runtime") {
    return minutes.low === minutes.high
      ? `about ${minutes.low} min`
      : `about ${minutes.low}–${minutes.high} min`;
  }
  const typical = templatesOf(catalog, serviceId)
    .map((template) => template.typicalRuntimeSeconds)
    .find((seconds) => seconds != null);
  return aboutRuntime(typical) ?? "you choose the time limit";
}

/** How long a service's jobs typically run, in minutes: its questions' options, else its template's typical runtime. */
function serviceMinutes(
  catalog: Catalog,
  serviceId: string,
): { low: number; high: number; from: "options" | "runtime" } | null {
  const own = templatesOf(catalog, serviceId);
  const minutes = own.flatMap((template) =>
    template.questions.flatMap((question) =>
      question.options.flatMap((option) => (option.minutes == null ? [] : [option.minutes])),
    ),
  );
  if (minutes.length > 0) {
    return { low: Math.min(...minutes), high: Math.max(...minutes), from: "options" };
  }
  const seconds = own.map((template) => template.typicalRuntimeSeconds).find((value) => value != null);
  return seconds == null ? null : { low: seconds / 60, high: seconds / 60, from: "runtime" };
}

/**
 * What a service typically costs (ADR 0058): its shortest typical run at the low end of the typical price an hour of
 * the smallest GPU size its first template fits on, to its longest at the high end; the same typical prices the website
 * shows. Null when either isn't known: never a guess.
 */
export function serviceTypicalCost(
  catalog: Catalog,
  serviceId: string,
  sizes: ReadonlyArray<{ gpuMemoryGb: number; typicalPricePaisePerHour: { min: number; max: number } }>,
): string | null {
  const minutes = serviceMinutes(catalog, serviceId);
  const template = templatesOf(catalog, serviceId)[0];
  if (!minutes || !template) {
    return null;
  }
  const size = [...sizes]
    .sort((a, b) => a.gpuMemoryGb - b.gpuMemoryGb)
    .find((each) => each.gpuMemoryGb * 1000 >= template.minVramMb);
  if (!size) {
    return null;
  }
  const cost = (at: number, perHour: number) => Math.max(1, Math.round((at * perHour) / 60));
  const low = cost(minutes.low, size.typicalPricePaisePerHour.min);
  const high = cost(minutes.high, size.typicalPricePaisePerHour.max);
  return low === high ? `typically ${formatPaise(low)}` : `typically ${formatPaiseRange(low, high)}`;
}

/** One sentence on what a service does: its first template's description. */
export const serviceSentence = (catalog: Catalog, serviceId: string): string =>
  templatesOf(catalog, serviceId)[0]?.description ?? "";

/** Whether a template's service is one the catalog offers (not hidden): the only ones Book again and Run again offer. */
export const isOffered = (catalog: Catalog, templateId: string): boolean => {
  const service = serviceOf(catalog, templateId);
  return service !== undefined && offeredServices(catalog).some((offered) => offered.id === service.id);
};
