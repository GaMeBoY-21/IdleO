import {
  Activity,
  Box,
  Code,
  FlaskConical,
  Gamepad2,
  Layers,
  NotebookPen,
  Plug,
  Table,
  type LucideIcon,
} from "lucide-react";
import type { components } from "@/api/schema.gen";

type IconKey = components["schemas"]["ServiceResponse"]["icon"];

/** Each service icon key the catalog may name (templates/catalog.yaml), as the Lucide icon that draws it. */
export const SERVICE_ICONS: Record<IconKey, LucideIcon> = {
  layers: Layers,
  code: Code,
  table: Table,
  cube: Box,
  notebook: NotebookPen,
  plug: Plug,
  gamepad: Gamepad2,
  flask: FlaskConical,
  pulse: Activity,
};
