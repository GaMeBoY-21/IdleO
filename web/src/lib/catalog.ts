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
  const own = templatesOf(catalog, serviceId);
  const minutes = own.flatMap((template) =>
    template.questions.flatMap((question) =>
      question.options.flatMap((option) => (option.minutes == null ? [] : [option.minutes])),
    ),
  );
  if (minutes.length > 0) {
    const low = Math.min(...minutes);
    const high = Math.max(...minutes);
    return low === high ? `about ${low} min` : `about ${low}–${high} min`;
  }
  const typical = own.map((template) => template.typicalRuntimeSeconds).find((seconds) => seconds != null);
  return aboutRuntime(typical) ?? "you choose the time limit";
}

/** One sentence on what a service does: its first template's description. */
export const serviceSentence = (catalog: Catalog, serviceId: string): string =>
  templatesOf(catalog, serviceId)[0]?.description ?? "";

/** Whether a template's service is one the catalog offers (not hidden): the only ones Book again and Run again offer. */
export const isOffered = (catalog: Catalog, templateId: string): boolean => {
  const service = serviceOf(catalog, templateId);
  return service !== undefined && offeredServices(catalog).some((offered) => offered.id === service.id);
};
