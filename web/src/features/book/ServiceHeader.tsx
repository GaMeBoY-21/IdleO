import { Link } from "wouter";

/**
 * The top of a service's booking screen (docs/design/redesign/book-finetune-laptop.html): back to all services, the
 * service's name in big type, and a line on what you bring and what you get, the template's own.
 */
export function ServiceHeader({
  name,
  description,
  laptop,
}: {
  name: string;
  description: string;
  laptop: boolean;
}) {
  return (
    <div className="flex flex-col gap-2">
      <Link
        href="/book/"
        className="flex min-h-touch items-center self-start text-small font-semibold text-text-muted"
      >
        <span aria-hidden="true">←&nbsp;</span>All services
      </Link>
      <h1 className={laptop ? "text-question" : "text-title"}>{name}</h1>
      <p className="text-body text-text-muted">{description}</p>
    </div>
  );
}
