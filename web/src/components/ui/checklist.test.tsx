import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Checklist, type Step } from "./Checklist";

vi.mock("wouter", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const step = (title: string, done: boolean | null): Step => ({
  title,
  detail: `${title}, in words`,
  done,
  action: { href: `/${title.toLowerCase()}/`, label: `Do ${title}` },
});

describe("a first run's checklist (ADR 0058)", () => {
  it("ticks what's done, counts it, and offers each step not done", () => {
    render(
      <Checklist
        id="c"
        title="Get started"
        steps={[step("Terms", true), step("Credit", false), step("Test", false)]}
      />,
    );
    expect(screen.getByText("1 of 3 done")).toBeInTheDocument();
    expect(screen.getByText(": done")).toBeInTheDocument();
    expect(screen.getAllByText(": to do")).toHaveLength(2);
    expect(screen.queryByRole("link", { name: "Do Terms" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Do Credit" })).toHaveAttribute("href", "/credit/");
    expect(screen.getByRole("link", { name: "Do Test" })).toHaveAttribute("href", "/test/");
  });

  it("is gone once everything is done", () => {
    const { container } = render(
      <Checklist id="c" title="Get started" steps={[step("Terms", true), step("Credit", true)]} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("waits until every step is known, so it never shows and then goes", () => {
    const { container } = render(
      <Checklist id="c" title="Get started" steps={[step("Terms", true), step("Credit", null)]} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
