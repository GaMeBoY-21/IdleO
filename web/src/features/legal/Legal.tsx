import { Link } from "wouter";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { useTitle } from "@/lib/title";

/**
 * The terms and the privacy notice are being drafted by a lawyer (docs/plan/product-surface.md 11.14), so each page
 * says so and sends the reader to the person who has the current text.
 */
function Placeholder({ title }: { title: string }) {
  useTitle(title);
  return (
    <SignInFrame title={title}>
      <p className="mb-8 text-body text-text-muted">Being drafted. Ask Nikki for the current version.</p>
      <Link href="/sign-in/" className="text-small underline">
        Back to sign in
      </Link>
    </SignInFrame>
  );
}

export function Terms() {
  return <Placeholder title="Terms" />;
}

export function Privacy() {
  return <Placeholder title="Privacy" />;
}
