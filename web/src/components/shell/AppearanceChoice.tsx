import { Monitor, Moon, Sun } from "lucide-react";
import { useState } from "react";
import { config } from "@/config";
import { applyThemeChoice, readThemeChoice, type ThemeChoice } from "@/design/theme";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

const choices: { value: ThemeChoice; label: string; icon: typeof Sun }[] = [
  { value: "system", label: "Match my device", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
];

/**
 * The theme, in Account's rail from 768 px (the avatar is a link there, so the menu's theme choice has moved here):
 * Match my device, Day Shift or Night Shift, remembered on this browser, and shared with the website. One group of radios, as the menu's was.
 */
export function AppearanceChoice() {
  const [theme, setTheme] = useState<ThemeChoice>(readThemeChoice);
  return (
    <Tile as="div" className="flex flex-col gap-2 p-2.5">
      <p id="appearance-title" className="px-4 pt-2 text-caption font-medium text-text-2">
        Appearance
      </p>
      <div role="radiogroup" aria-labelledby="appearance-title" className="flex flex-col gap-0.5">
        {choices.map(({ value, label, icon: Icon }) => (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={theme === value}
            onClick={() => {
              setTheme(value);
              applyThemeChoice(value, config.siteUrl);
            }}
            className={cn(
              "flex min-h-touch items-center gap-3 rounded-control px-4 text-small",
              theme === value ? "bg-canvas font-bold" : "font-semibold text-text-2 hover:text-text",
            )}
          >
            <Icon aria-hidden="true" className="size-5" strokeWidth={1.75} />
            {label}
          </button>
        ))}
      </div>
    </Tile>
  );
}
