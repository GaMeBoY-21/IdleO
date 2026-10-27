import { Link } from "wouter";
import { buttonClasses } from "@/components/ui/Button";
import { SignInFrame } from "@/features/sign-in/SignInFrame";
import { useTitle } from "@/lib/title";

export function NotFound() {
  useTitle("Not found");
  return (
    <SignInFrame title="We can't find that page">
      <p className="mb-8 text-body text-text-2">The link may be out of date, or mistyped.</p>
      <Link href="/" className={buttonClasses("primary", true, true)}>
        Go to your home
      </Link>
    </SignInFrame>
  );
}
