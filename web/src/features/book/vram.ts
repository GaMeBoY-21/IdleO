import type { Template } from "@/api/queries";
import { formatBytes } from "@/lib/bytes";

/**
 * GPU memory for a booking (ADR 0022), the server's rule (SessionVram, GpuClasses) mirrored, so the screen can say
 * what a model needs while it's still uploading; the server works it out again from the upload, and its answer, in
 * the quote, is final.
 */

/** The classes renters choose between, in GB; a class asks for its size in thousands of MB, which every card has. */
export const GPU_CLASSES_GB = [8, 12, 16, 24] as const;
export const classMb = (gb: number) => gb * 1_000;
/** The most memory any GPU here has: above this, nothing fits. */
export const BIGGEST_MB = classMb(GPU_CLASSES_GB[GPU_CLASSES_GB.length - 1]!);

const GIB = 1024 ** 3;

/** The smallest class with at least `mb`; `mb` itself when it's above every class. */
export const roundUpToClass = (mb: number) => GPU_CLASSES_GB.map(classMb).find((size) => size >= mb) ?? mb;

/**
 * What the template needs for the model as given: when it sizes memory from an input (`vramFromInput`) and that
 * input is an upload of `modelBytes`, `baseMb` plus `perGbMb` for each GiB (part GiBs in proportion, rounded up),
 * never below the template's minimum, rounded up to a class. A link's size isn't known (null): the minimum.
 */
export function neededMb(template: Template, modelBytes: number | null): number {
  const rule = template.vramFromInput;
  if (!rule || modelBytes === null) {
    return template.minVramMb;
  }
  const grows = Math.ceil((rule.perGbMb * modelBytes) / GIB);
  return roundUpToClass(Math.max(template.minVramMb, rule.baseMb + grows));
}

/** "14.2 GB": a model's size as the GPU line says it, one decimal; a small one as formatBytes says it ("24 MB"). */
export const modelGigabytes = (bytes: number) =>
  bytes < GIB ? formatBytes(bytes) : `${(bytes / GIB).toFixed(1)} GB`;

/** "an 8 GB GPU", "a 12 GB GPU": the article for a size said aloud. */
export const withSizeArticle = (size: string) => (/^(8|11|18)\b/.test(size) ? `an ${size}` : `a ${size}`);

/** "12 GB": a class, or a need above every class, in whole GB ("30 GB"). */
export const classGigabytes = (mb: number) => `${Math.round(mb / 1_000)} GB`;
