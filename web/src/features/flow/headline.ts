import type { Session } from "@/api/queries";
import type { components } from "@/api/schema.gen";
import { serviceOf } from "@/lib/catalog";
import { gpuName, withArticle } from "@/lib/gpu";

type Catalog = components["schemas"]["TemplateList"];

/**
 * What a live session is doing, in a few words: "Fine-tuning", "Finding your GPU". Home's running bar and the
 * session screen's state both say it this way.
 */
export function headline(session: Session, catalog: Catalog): string {
  switch (session.state) {
    case "REQUESTED":
    case "MATCHED":
      return "Finding your GPU";
    case "PROVISIONING":
      return "GPU found";
    case "HOST_LOST":
      return "Moving to another GPU";
    case "FINISHING":
      return "Saving your outputs";
    default:
      return serviceOf(catalog, session.templateId)?.runningLabel ?? "Running";
  }
}

/**
 * The live session's title (docs/design/redesign/live-laptop.html): "Fine-tuning on an RTX 4080 Laptop" while it
 * runs; before and after that, what it's doing.
 */
export function sessionTitle(session: Session, catalog: Catalog): string {
  const doing = headline(session, catalog);
  return session.state === "RUNNING" && session.gpu
    ? `${doing} on ${withArticle(gpuName(session.gpu.model))}`
    : doing;
}
