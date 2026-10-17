import { useEffect, useState } from "react";
import { useLocation } from "wouter";
import type { components } from "@/api/schema.gen";
import { fromDrop, type DroppedEntry } from "@/features/book/chosen-files";
import { holdDrop, isModelFolder } from "@/features/book/dropped";
import { bookHref } from "@/lib/catalog";

type Service = components["schemas"]["ServiceResponse"];

/** The service a drop goes to: a model folder to Fine-tune, anything else to Run your code; else the first offered. */
export function serviceForDrop(services: Service[], model: boolean): Service | undefined {
  return services.find((service) => service.id === (model ? "fine-tune" : "run-code")) ?? services[0];
}

/**
 * Dropping a folder anywhere on Home (web/DESIGN.md "Home"): while Home is on a laptop's screen, a file or folder
 * dropped on the page is read as the booking screen reads one, held, and the matching service opened, which takes it
 * as its first upload step. Anything with a `config.json` at its top goes to Fine-tune. Returns whether one is being
 * dragged over, for the dark tile to show.
 */
export function useDropAnywhere(services: Service[], active: boolean): boolean {
  const [, navigate] = useLocation();
  const [over, setOver] = useState(false);
  useEffect(() => {
    if (!active) {
      return;
    }
    const files = (event: DragEvent) => event.dataTransfer?.types.includes("Files") ?? false;
    const enter = (event: DragEvent) => {
      if (files(event)) {
        event.preventDefault();
        setOver(true);
      }
    };
    const leave = (event: DragEvent) => {
      if (event.relatedTarget === null) {
        setOver(false);
      }
    };
    const drop = (event: DragEvent) => {
      if (!files(event)) {
        return;
      }
      event.preventDefault();
      setOver(false);
      const item = event.dataTransfer?.items[0];
      const entry = (item?.webkitGetAsEntry() ?? null) as DroppedEntry | null;
      void fromDrop(entry, event.dataTransfer?.files[0] ?? null).then((chosen) => {
        const service = chosen && serviceForDrop(services, isModelFolder(chosen));
        if (chosen && service) {
          holdDrop(chosen);
          navigate(bookHref(service.id));
        }
      });
    };
    document.addEventListener("dragenter", enter);
    document.addEventListener("dragover", enter);
    document.addEventListener("dragleave", leave);
    document.addEventListener("drop", drop);
    return () => {
      document.removeEventListener("dragenter", enter);
      document.removeEventListener("dragover", enter);
      document.removeEventListener("dragleave", leave);
      document.removeEventListener("drop", drop);
    };
  }, [active, services, navigate]);
  return over;
}
