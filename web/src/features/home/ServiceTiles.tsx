import { Link } from "wouter";
import type { components } from "@/api/schema.gen";
import { buttonClasses } from "@/components/ui/Button";
import { Tile, TileLabel, TileLink, type TileTone } from "@/components/ui/Tile";
import { bookHref, sentence } from "@/lib/catalog";
import { SERVICE_ICONS } from "@/lib/service-icons";

type Service = components["schemas"]["ServiceResponse"];

/** The accent each service has, by its place in the catalog: ember, then ion, then a plain panel (the approved reference). */
export function serviceTone(index: number): TileTone {
  return index === 0 ? "ember" : index === 1 ? "ion" : "panel";
}

/**
 * The first service, as the tall ember tile: its icon, its name and what you bring. It opens Book at it. The catalog
 * decides which service is first, so a different one is a change there.
 */
export function MainServiceTile({ service }: { service: Service }) {
  const Icon = SERVICE_ICONS[service.icon];
  return (
    <TileLink href={bookHref(service.id)} tone={serviceTone(0)} className="row-span-2">
      <Icon aria-hidden="true" className="size-7.5" strokeWidth={1.6} />
      <span className="flex flex-col gap-0.5">
        <span className="font-title text-display-card break-words">{service.name}</span>
        <TileLabel tone="ember">{sentence(service.tagline)}</TileLabel>
      </span>
    </TileLink>
  );
}

/** Any other service: a wide tile, its name and what you bring, its icon at the end. */
export function WideServiceTile({ service, index = 1 }: { service: Service; index?: number }) {
  const Icon = SERVICE_ICONS[service.icon];
  const tone = serviceTone(index);
  return (
    <TileLink
      href={bookHref(service.id)}
      tone={tone}
      className="col-span-2 flex-row items-center justify-between gap-3"
    >
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="font-title text-display-card break-words">{service.name}</span>
        <TileLabel tone={tone}>{sentence(service.tagline)}</TileLabel>
      </span>
      <Icon aria-hidden="true" className="size-7.5 shrink-0" strokeWidth={1.6} />
    </TileLink>
  );
}

/**
 * With nothing running, the first tile is the question itself, with the services inside it (the first as the volt
 * button, the others as ghost ones), so there is one way to start and no tile twice.
 */
export function QuestionTile({ services }: { services: Service[] }) {
  return (
    <Tile
      tone="night"
      aria-labelledby="question"
      className={services.length > 2 ? "col-span-2 row-span-3" : "col-span-2 row-span-2"}
    >
      <h1 id="question" className="font-title text-display-card">
        What do you want to run?
      </h1>
      <div className="mt-3 flex flex-col gap-2">
        {services.map((service, index) => {
          const Icon = SERVICE_ICONS[service.icon];
          return (
            <Link
              key={service.id}
              href={bookHref(service.id)}
              className={buttonClasses(index === 0 ? "live" : "ghost", true)}
            >
              <Icon aria-hidden="true" className="size-5" strokeWidth={1.75} />
              {service.name}
            </Link>
          );
        })}
      </div>
    </Tile>
  );
}
