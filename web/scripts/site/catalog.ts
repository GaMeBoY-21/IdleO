// What the docs and the landing page say about the services, read from templates/catalog.yaml when the site is built
// (ADR 0029). A service shows only if the catalog offers it (not `hidden`). Its inputs come from the catalog's own
// template for it; while the signed templates are still to come (the hardware track), from the development catalog's
// template for the same service, which mirrors the real one's inputs, questions and parameters (templates/README.md).
import { readFileSync } from "node:fs";
import { parse } from "yaml";
import type { ServiceInfo, ServiceInput, ServiceQuestion } from "../../src/site/context.ts";
import { EXAMPLE_CLASS_GB } from "../../src/site/examples.ts";
import type { WorkedExample } from "../../src/site/pricing.ts";

interface RawOption {
  id: string;
  label: string;
  minutes: number;
}
interface RawQuestion {
  preset?: string;
  param?: string;
  gpu_memory?: boolean;
  label?: string;
  help?: string;
  default?: string;
  options?: RawOption[];
  choices_gb?: number[];
}
interface RawParam {
  name: string;
  label: string;
  help?: string;
}
interface RawTemplate {
  id: string;
  service: string;
  description?: string;
  you_get?: string;
  inputs?: {
    name: string;
    label: string;
    help?: string;
    kind: "folder" | "file";
    accepts: string[];
    required?: boolean;
    max_size_gb?: number;
  }[];
  questions?: RawQuestion[];
  params?: RawParam[];
}
interface RawCatalog {
  services: { id: string; name: string; tagline: string; hidden?: boolean }[];
  templates: RawTemplate[];
}

const read = (path: string): RawCatalog => parse(readFileSync(path, "utf8")) as RawCatalog;

function questions(template: RawTemplate): ServiceQuestion[] {
  const params = new Map((template.params ?? []).map((param) => [param.name, param]));
  return (template.questions ?? []).flatMap((question): ServiceQuestion[] => {
    if (question.preset) {
      return [
        {
          label: question.label ?? question.preset,
          help: question.help ?? null,
          choices: (question.options ?? []).map(
            (option) => `${option.label} (about ${option.minutes} minutes, typical)`,
          ),
        },
      ];
    }
    if (question.param) {
      const param = params.get(question.param);
      return [
        {
          label: question.label ?? param?.label ?? question.param,
          help: question.help ?? param?.help ?? null,
          choices: [],
        },
      ];
    }
    if (question.gpu_memory) {
      return [
        {
          label: question.label ?? "GPU memory",
          help: question.help ?? null,
          choices: (question.choices_gb ?? []).map((gb) => `${gb} GB`),
        },
      ];
    }
    return [];
  });
}

/** The services the catalog offers, with what each takes. */
export function loadServices(catalogPath: string, developmentPath: string): ServiceInfo[] {
  const catalog = read(catalogPath);
  const development = read(developmentPath);
  return catalog.services
    .filter((service) => !service.hidden)
    .map((service): ServiceInfo => {
      const own = catalog.templates.find((template) => template.service === service.id);
      const template = own ?? development.templates.find((candidate) => candidate.service === service.id);
      const inputs: ServiceInput[] = (template?.inputs ?? []).map((input) => ({
        name: input.name,
        label: input.label,
        help: input.help ?? "",
        kind: input.kind,
        accepts: input.accepts,
        required: input.required ?? false,
        maxSizeGb: input.max_size_gb ?? null,
      }));
      return {
        id: service.id,
        name: service.name,
        tagline: service.tagline,
        description: template?.description?.replace(/ Simulated on the development runner\.$/, "") ?? null,
        youGet: template?.you_get ?? null,
        inputs,
        questions: template ? questions(template) : [],
        source: own ? "catalog" : "development",
      };
    });
}

/**
 * The pricing page's worked examples, from the catalog's own typical minutes: the options of the first preset question
 * of each service that has one, the quickest and the one it starts on, priced on the example class. Services with no
 * typical time in the catalog have none, and are not made up.
 */
export function workedExamples(catalogPath: string, developmentPath: string): WorkedExample[] {
  const catalog = read(catalogPath);
  const development = read(developmentPath);
  const examples: WorkedExample[] = [];
  for (const service of catalog.services.filter((candidate) => !candidate.hidden)) {
    const template =
      catalog.templates.find((candidate) => candidate.service === service.id) ??
      development.templates.find((candidate) => candidate.service === service.id);
    const preset = template?.questions?.find((question) => question.preset && question.options?.length);
    if (!preset?.options) {
      continue;
    }
    const byMinutes = [...preset.options].sort((a, b) => a.minutes - b.minutes);
    const started = preset.options.find((option) => option.id === preset.default) ?? byMinutes[0]!;
    for (const option of new Set([byMinutes[0]!, started])) {
      examples.push({
        label: `${service.name}: ${option.label}`,
        minutes: option.minutes,
        gpuMemoryGb: EXAMPLE_CLASS_GB,
      });
    }
  }
  return examples.slice(0, 2);
}
