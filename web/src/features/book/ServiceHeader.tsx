import { PageTitle } from "@/components/ui/Headers";
import { Link } from "wouter";

/**
 * The top of a service's booking screen: back to all services, the
 * service's name in big type, and a line on what you bring and what you get, the template's own.
 */
export function ServiceHeader({ name, description }: { name: string; description: string }) {
  return (
    <div className="flex flex-col gap-2">
      <Link
        href="/book/"
        className="flex min-h-touch items-center self-start rounded-full border border-hairline bg-surface-1 px-4 text-small font-semibold hover:bg-surface-3"
      >
        <span aria-hidden="true">←&nbsp;</span>All services
      </Link>
      <PageTitle>{name}</PageTitle>
      <p className="text-body text-ink-subtle">{description}</p>
    </div>
  );
}
