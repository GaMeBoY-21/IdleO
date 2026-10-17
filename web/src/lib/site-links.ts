import { config } from "@/config";

/** An address on the public website (ADR 0029), which has its own address apart from the app's. */
export const siteLink = (path: string): string => `${config.siteUrl}${path}`;
