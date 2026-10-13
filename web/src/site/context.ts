import type { WorkedExample } from "./pricing";

/** What an input a service takes looks like to a reader (from templates/catalog.yaml). */
export interface ServiceInput {
  name: string;
  label: string;
  help: string;
  kind: "folder" | "file";
  accepts: string[];
  required: boolean;
  maxSizeGb: number | null;
}

export interface ServiceQuestion {
  label: string;
  help: string | null;
  /** The choices, when it's a choice: the preset's options, or the GPU memory sizes. */
  choices: string[];
}

export interface ServiceInfo {
  id: string;
  name: string;
  tagline: string;
  /** What the renter brings, and what they get back, from the template. */
  description: string | null;
  youGet: string | null;
  inputs: ServiceInput[];
  questions: ServiceQuestion[];
  /** Where the inputs came from: the production catalog's own template, or the development catalog's mirror. */
  source: "catalog" | "development";
}

/** One page of the docs, its body already rendered to HTML at build time. */
export interface DocPage {
  /** "/docs/", "/docs/cli/"… */
  path: string;
  title: string;
  description: string;
  html: string;
}

/** Everything a build knows that the pages need (scripts/build-site.ts makes it). */
export interface SiteContext {
  /** The product's API origin, for the pages that ask it for live prices; null if the build has none. */
  apiUrl: string | null;
  services: ServiceInfo[];
  examples: WorkedExample[];
  docs: DocPage[];
}

/** What a page needs the document around it to say and load. */
export interface PageDef {
  path: string;
  title: string;
  description: string;
  body: React.ReactNode;
  /** The scripts the page loads: none, unless it has something live. */
  islands: ("pricing" | "earn")[];
}
