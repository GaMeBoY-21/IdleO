import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { LatestRelease } from "@/api/hosting";
import { AddMachine } from "./AddMachine";

const mocks = vi.hoisted(() => ({
  release: {
    data: undefined as LatestRelease | null | undefined,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  },
}));

vi.mock("@/api/hosting", () => ({ useLatestRelease: () => mocks.release }));

const release: LatestRelease = {
  version: "0.2.0",
  setupSizeBytes: 9_800_000,
  manifest: "{}",
  signature: "",
  urls: {
    app: "https://storage.example.invalid/app",
    distro: "https://storage.example.invalid/distro",
    setup: "https://storage.example.invalid/setup",
  },
};

describe("Add a machine's download", () => {
  it("offers the Windows app with its version and size once there's a release", () => {
    Object.assign(mocks.release, { data: release, isPending: false, isError: false });
    render(<AddMachine />);
    const download = screen.getByRole("link", { name: "Download for Windows" });
    expect(download.getAttribute("href")).toMatch(/\/v1\/agent-releases\/latest\/setup$/);
    expect(screen.getByText("v0.2.0 · 9.8 MB")).toBeInTheDocument();
  });

  it("says it's coming, and offers no download, while there's no release", () => {
    Object.assign(mocks.release, { data: null, isPending: false, isError: false });
    render(<AddMachine />);
    expect(screen.getByRole("button", { name: "Windows app: coming soon" })).toBeDisabled();
    expect(screen.queryByRole("link", { name: /Download/ })).toBeNull();
  });

  it("holds the button's place while it asks, and offers to ask again if it can't", async () => {
    Object.assign(mocks.release, { data: undefined, isPending: true, isError: false });
    const { unmount } = render(<AddMachine />);
    expect(screen.getByLabelText("Checking for the Windows app")).toBeInTheDocument();
    unmount();
    Object.assign(mocks.release, { data: undefined, isPending: false, isError: true });
    render(<AddMachine />);
    await userEvent.click(screen.getByRole("button", { name: /Try again/ }));
    expect(mocks.release.refetch).toHaveBeenCalled();
  });
});
