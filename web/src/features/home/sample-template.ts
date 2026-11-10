import { formatPaise } from "@/lib/money";

/** The first job a renter runs (ADR 0058): the CUDA test, which proves a GPU reaches their container. */
export const SAMPLE_TEMPLATE = "cuda-smoke-test";

/** The one-click button's words: its spending cap, the template's own usual budget. */
export const sampleLabel = (budgetPaise: number) => `Run it now · up to ${formatPaise(budgetPaise)}`;
