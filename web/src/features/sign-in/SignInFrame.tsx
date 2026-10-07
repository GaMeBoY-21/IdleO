import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";

/** The signed-out screens: the wordmark and one card, centred, the same on a phone and a laptop. */
export function SignInFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center px-4 py-12 md:justify-center">
      <div className="w-full max-w-narrow">
        <Wordmark className="mb-8 h-8 w-auto" />
        <h1 className="mb-2 text-heading font-semibold">{title}</h1>
        {children}
      </div>
    </main>
  );
}
