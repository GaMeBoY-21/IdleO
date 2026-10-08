/** "RTX 4080 Laptop": a GPU's name as people say it, without the maker's prefix. */
export const gpuName = (model: string) =>
  model
    .replace(/^NVIDIA\s+/i, "")
    .replace(/^GeForce\s+/i, "")
    .replace(/\s+GPU$/i, "");

/** GPU memory as renters read it: "12 GB", from the MB the API counts. */
export const gigabytes = (mb: number) => `${Math.round(mb / 1024)} GB`;

/**
 * "an RTX 4080 Laptop", "a GTX 1660": the article for a GPU's spoken name. A name that starts with a vowel, or with
 * a capitalised abbreviation whose first letter sounds like one (F, H, L, M, N, R, S, X), takes "an".
 */
export const withArticle = (name: string) =>
  /^([AEIOUaeiou]|[FHLMNRSX][A-Z0-9]*\b)/.test(name) ? `an ${name}` : `a ${name}`;
