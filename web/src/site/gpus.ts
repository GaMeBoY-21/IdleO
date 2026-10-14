/**
 * What each GPU memory class typically runs (ADR 0029). Nothing here is measured on our hosts: every line is what a
 * card of that size typically manages, from the plan's table and general knowledge, and the page says "typical". The
 * classes are the ones renters can ask for (ADR 0022).
 */
export interface GpuClassInfo {
  gb: number;
  /** The main consumer cards with this much memory, laptop and desktop. */
  cards: string[];
  /** The largest model a QLoRA fine-tune typically takes. */
  qlora: string;
  /** A typical training batch. */
  batch: string;
  /** Running a model to answer prompts. */
  inference: string;
  /** Other work that fits. */
  also: string;
}

export const GPU_CLASSES: GpuClassInfo[] = [
  {
    gb: 8,
    cards: ["RTX 3060 Ti", "RTX 3070", "RTX 4060", "RTX 4060 Laptop", "RTX 4070 Laptop"],
    qlora: "Models of 1 to 3 billion parameters",
    batch: "1 to 2 examples at a time, with short sequences",
    inference: "7B models at 4-bit",
    also: "Classic machine learning, and training your own models of up to about 100 million parameters",
  },
  {
    gb: 12,
    cards: ["RTX 3060 (12 GB)", "RTX 4070", "RTX 4070 Super", "RTX 4080 Laptop"],
    qlora: "Models up to about 8 billion parameters",
    batch: "2 to 4 examples at a time, with sequences of 1,000 to 2,000 tokens",
    inference: "7 to 8B models comfortably, 13 to 14B at 4-bit",
    also: "Everything in 8 GB, with room for larger batches and longer contexts",
  },
  {
    gb: 16,
    cards: ["RTX 4060 Ti 16 GB", "RTX 4070 Ti Super", "RTX 4080", "RTX 4090 Laptop", "RTX 5080"],
    qlora: "Models of 7 to 8 billion parameters with longer sequences, and some 13B",
    batch: "4 to 8 examples at a time",
    inference: "14B models at 4-bit with a longer context",
    also: "Larger batches for your own models, and training ones of a few hundred million parameters",
  },
  {
    gb: 24,
    cards: ["RTX 3090", "RTX 3090 Ti", "RTX 4090", "RTX 5090 Laptop"],
    qlora: "Models up to about 13 billion parameters",
    batch: "8 or more examples at a time, with sequences of 2,000 tokens and more",
    inference: "30B-class models at 4-bit",
    also: "Training your own models from scratch, of up to about a billion parameters",
  },
];
