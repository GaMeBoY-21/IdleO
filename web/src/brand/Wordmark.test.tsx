import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { brand } from "./brand";
import { Wordmark } from "./Wordmark";

it("reads as the name, whose letters are what scripts/wordmark-path.py drew", () => {
  // A new name needs a new outline: run the script with it, then change this.
  expect(brand.name.toLowerCase()).toBe("idleo");
  render(<Wordmark />);
  expect(screen.getByText("idleo")).toHaveClass("sr-only");
});
