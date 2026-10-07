import { useEffect } from "react";
import { brand } from "@/brand/brand";

/** The page's title, "Credit · IdleO", so tabs and history say where they are. */
export function useTitle(title: string | null): void {
  useEffect(() => {
    document.title = title ? `${title} · ${brand.name}` : brand.name;
  }, [title]);
}
