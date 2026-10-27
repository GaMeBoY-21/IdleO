import { ArrowRight } from "lucide-react";
import type { components } from "@/api/schema.gen";
import { Tile, TileLink } from "@/components/ui/Tile";
import { bookHref, serviceSentence, serviceTime } from "@/lib/catalog";
import { cn } from "@/lib/cn";
import { SERVICE_ICONS } from "@/lib/service-icons";

type Service = components["schemas"]["ServiceResponse"];
type Catalog = components["schemas"]["TemplateList"];

/**
 * Home's dark tile on a laptop (docs/design/bento/laptop/home-laptop.html): the question, and the services from the
 * catalog as big tiles beside each other, the first lime and the others white, each with its typical time, name,
 * sentence and "Start". Dropping a folder anywhere on Home opens the matching service with it (HomeDrop).
 */
export function ServicesTile({
  services,
  catalog,
  dropping,
}: {
  services: Service[];
  catalog: Catalog;
  dropping: boolean;
}) {
  return (
    <Tile
      tone="night"
      size="lg"
      aria-labelledby="question"
      className={cn(
        "flex flex-col gap-5 md:col-span-6 xl:col-span-8 xl:gap-6",
        dropping && "ring-4 ring-volt",
      )}
    >
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-1">
        <h1 id="question" className="text-question">
          What do you want to run?
        </h1>
        <span className="text-caption text-on-night-2">Or drop a folder anywhere on this page</span>
      </div>
      <div className="grid flex-1 gap-3.5 md:grid-cols-2">
        {services.map((service, index) => {
          const Icon = SERVICE_ICONS[service.icon];
          const first = index === 0;
          return (
            <TileLink
              key={service.id}
              href={bookHref(service.id)}
              tone={first ? "volt" : "panel"}
              size="lg"
              className="gap-4"
            >
              <span className="flex items-start justify-between gap-3">
                <span
                  aria-hidden="true"
                  className={cn(
                    "flex size-12 items-center justify-center rounded-icon",
                    first ? "bg-ink/10" : "bg-canvas",
                  )}
                >
                  <Icon className="size-6" strokeWidth={1.7} />
                </span>
                <span className={cn("text-caption font-semibold", first ? "text-ink" : "text-text-2")}>
                  {serviceTime(catalog, service.id)}
                </span>
              </span>
              <span className="flex flex-col gap-1.5">
                <span className="text-title break-words">{service.name}</span>
                <span className={cn("max-w-prose text-body", first ? "text-ink" : "text-text-2")}>
                  {serviceSentence(catalog, service.id)}
                </span>
              </span>
              <span className="flex items-center justify-between text-small font-bold">
                <span>Start</span>
                <ArrowRight aria-hidden="true" className="size-5" strokeWidth={2.2} />
              </span>
            </TileLink>
          );
        })}
      </div>
    </Tile>
  );
}
