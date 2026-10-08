import { Link } from "wouter";
import type { components } from "@/api/schema.gen";
import { IconTile } from "@/components/ui/Option";
import { Skeleton } from "@/components/ui/Skeleton";
import { bookHref } from "@/lib/catalog";
import { SERVICE_ICONS } from "@/lib/service-icons";

type Service = components["schemas"]["ServiceResponse"];

/** A tagline as a line of its own: "A model on your data". */
export const sentence = (tagline: string) => tagline.charAt(0).toUpperCase() + tagline.slice(1);

/**
 * The services, each a tile that opens Book at it (docs/design/redesign/home-phone.html): two columns on a phone.
 * On a laptop they're a list of rows, as Book's left panel shows them.
 */
export function ServiceTiles({ services, laptop }: { services: Service[]; laptop: boolean }) {
  if (laptop) {
    return (
      <ul aria-label="Services" className="flex flex-col gap-2">
        {services.map((service) => (
          <li key={service.id}>
            <Link
              href={bookHref(service.id)}
              className="flex items-center gap-4 rounded-control px-4 py-3.5 hover:bg-fill"
            >
              <IconTile icon={SERVICE_ICONS[service.icon]} />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="text-option font-semibold break-words">{service.name}</span>
                <span className="text-small text-text-muted">{sentence(service.tagline)}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    );
  }
  return (
    <ul aria-label="Services" className="grid grid-cols-2 gap-3">
      {services.map((service) => {
        const Icon = SERVICE_ICONS[service.icon];
        return (
          <li key={service.id} className="flex">
            <Link
              href={bookHref(service.id)}
              className="flex w-full flex-col gap-3 rounded-control bg-fill p-3.5 hover:bg-fill-selected"
            >
              <Icon aria-hidden="true" className="size-6.5" strokeWidth={1.75} />
              <span className="flex flex-col gap-0.5">
                <span className="text-body font-semibold break-words">{service.name}</span>
                <span className="text-caption text-text-muted">{service.tagline}</span>
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

/** Two tiles' worth of grey, the height of a tile, while the catalog loads. */
export function ServiceTilesSkeleton({ laptop }: { laptop: boolean }) {
  return laptop ? (
    <div aria-hidden="true" className="flex flex-col gap-2">
      {[0, 1, 2, 3].map((key) => (
        <Skeleton key={key} className="h-18 w-full" />
      ))}
    </div>
  ) : (
    <div aria-hidden="true" className="grid grid-cols-2 gap-3">
      {[0, 1, 2, 3].map((key) => (
        <Skeleton key={key} className="h-24 w-full" />
      ))}
    </div>
  );
}
