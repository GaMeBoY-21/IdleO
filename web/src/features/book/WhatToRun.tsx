import type { components } from "@/api/schema.gen";
import { Option } from "@/components/ui/Option";
import { aboutRuntime, sentence } from "@/lib/catalog";
import { cn } from "@/lib/cn";
import { gigabytes } from "@/lib/gpu";
import { SERVICE_ICONS } from "@/lib/service-icons";

type Service = components["schemas"]["ServiceResponse"];
type Template = components["schemas"]["TemplateResponse"];

/** A template in a few words: "At least 8 GB · about 45 min". */
const facts = (template: Template) =>
  [
    template.ownImage ? "Your own container image, checked first" : null,
    `At least ${gigabytes(template.minVramMb)}`,
    aboutRuntime(template.typicalRuntimeSeconds),
  ]
    .filter(Boolean)
    .join(" · ");

/**
 * What to run: the services as tiles, the chosen one lime with a check. Each says what it does and, when it has one way to run it, how long that usually takes.
 */
export function ServiceChoice({
  services,
  templatesOf,
  selected,
  onPick,
}: {
  services: Service[];
  templatesOf: (serviceId: string) => Template[];
  selected: string | null;
  onPick: (serviceId: string) => void;
}) {
  return (
    <div role="group" aria-label="What to run" className="flex flex-col gap-2">
      {services.map((service) => {
        const [only, ...others] = templatesOf(service.id);
        const runtime = only && others.length === 0 ? aboutRuntime(only.typicalRuntimeSeconds) : null;
        return (
          <Option
            key={service.id}
            icon={SERVICE_ICONS[service.icon]}
            title={service.name}
            detail={[sentence(service.tagline), runtime].filter(Boolean).join(" · ")}
            selected={service.id === selected}
            onClick={() => onPick(service.id)}
          />
        );
      })}
    </div>
  );
}

/** Inside a service with more than one template, which one: a short list, the chosen one lime. */
export function TemplateChoice({
  templates,
  selected,
  onPick,
}: {
  templates: Template[];
  selected: string;
  onPick: (templateId: string) => void;
}) {
  return (
    <div role="group" aria-labelledby="template-title" className="flex flex-col gap-2">
      <p id="template-title" className="text-small font-medium text-text-2">
        Which one
      </p>
      {templates.map((template) => {
        const chosen = template.id === selected;
        return (
          <button
            key={template.id}
            type="button"
            aria-pressed={chosen}
            onClick={() => onPick(template.id)}
            className={cn(
              "flex flex-col gap-0.5 rounded-panel px-4.5 py-3 text-left transition-colors",
              chosen ? "bg-volt text-ink" : "bg-panel hover:bg-raised",
            )}
          >
            <span className="text-body font-semibold break-words">{template.name}</span>
            <span className={cn("text-small", chosen ? "text-ink" : "text-text-2")}>{facts(template)}</span>
          </button>
        );
      })}
    </div>
  );
}
