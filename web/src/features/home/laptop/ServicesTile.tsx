import type { components } from "@/api/schema.gen";
import { TileLink } from "@/components/ui/Tile";
import { bookHref, serviceSentence, serviceTime, serviceTypicalCost } from "@/lib/catalog";
import { cn } from "@/lib/cn";
import { serviceTone } from "../ServiceTiles";

type Service = components["schemas"]["ServiceResponse"];
type Catalog = components["schemas"]["TemplateList"];

/**
 * The services, as accent cards in a row (approved reference `AppHome`): the first ember, the second ion, the others
 * panels, each with its typical time and typical cost as the small labels, its name, and what you bring. The catalog decides what is
 * here and in which order. Dropping a folder anywhere on Home opens the matching service with it (HomeDrop); while
 * one is over the page the row is ringed in the focus colour.
 */
export function ServicesTile({
  services,
  catalog,
  dropping,
  prices,
}: {
  services: Service[];
  catalog: Catalog;
  dropping: boolean;
  /** The typical price of each GPU size (`GET /v1/public/availability`), once it has arrived. */
  prices?: Parameters<typeof serviceTypicalCost>[2];
}) {
  return (
    <section
      aria-label="Services"
      className={cn(
        "grid cols-cards gap-4 rounded-panel",
        dropping && "ring-4 ring-focus ring-offset-4 ring-offset-canvas",
      )}
    >
      {services.map((service, index) => {
        const tone = serviceTone(index);
        return (
          <TileLink
            key={service.id}
            href={bookHref(service.id)}
            tone={tone}
            size="lg"
            className="min-h-service-card gap-4"
          >
            <span className="flex flex-col gap-1 text-caption font-bold tracking-wider uppercase">
              <span>{serviceTime(catalog, service.id)}</span>
              {/* Its line is kept from the start, so the cost arriving moves nothing. */}
              <span className="min-h-4.5 font-semibold tracking-normal normal-case">
                {prices ? serviceTypicalCost(catalog, service.id, prices) : null}
              </span>
            </span>
            <span className="flex flex-col gap-1">
              <span className="font-title text-display-card break-words">{service.name}</span>
              <span className={cn("text-small", tone === "panel" && "text-text-2")}>
                {serviceSentence(catalog, service.id)}
              </span>
            </span>
          </TileLink>
        );
      })}
    </section>
  );
}
