import { Component, type ReactNode } from "react";
import { ErrorPage } from "@/pages/ErrorPage";

/** Catches a screen that breaks while rendering, and offers to try again (web/DESIGN.md "States"). */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  override state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  override componentDidCatch(error: unknown): void {
    console.error(error);
  }

  override render() {
    return this.state.failed ? (
      <ErrorPage reset={() => this.setState({ failed: false })} />
    ) : (
      this.props.children
    );
  }
}
