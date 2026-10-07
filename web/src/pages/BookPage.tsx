import { Link } from "wouter";
import { buttonClasses } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { useTitle } from "@/lib/title";

/** The booking flow is the next round of work; this keeps its place in the navigation until then. */
export function BookPage() {
  useTitle("Book a GPU");
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-heading font-semibold">Book a GPU</h1>
      <Card>
        <div className="flex max-w-prose flex-col items-start gap-3">
          <p className="text-body font-medium">Booking is coming next</p>
          <p className="text-small text-text-muted">
            You&apos;ll choose a template, see the price a minute before you book, and watch the session run
            here. Until then, your credit and sessions are on your home screen.
          </p>
          <Link href="/" className={buttonClasses("primary")}>
            Back to home
          </Link>
        </div>
      </Card>
    </div>
  );
}
