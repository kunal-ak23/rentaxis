import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import FollowUpsWidget from "../FollowUpsWidget";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}));

// Mock @/i18n/routing Link to a plain anchor.
vi.mock("@/i18n/routing", () => ({
  Link: ({ href, children, className }: { href: string; children: React.ReactNode; className?: string }) => (
    <a href={href} className={className}>
      {children}
    </a>
  ),
}));

const makeFollowUp = (n: number) => ({
  id: `id-${n}`,
  leaseId: `lease-${n}`,
  summary: `Follow-up summary number ${n}`,
  followUpDate: `2026-05-${String(n).padStart(2, "0")}`,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("FollowUpsWidget", () => {
  describe("empty state", () => {
    beforeEach(() => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [],
      });
    });

    it("shows empty message when fetch returns empty array", async () => {
      render(<FollowUpsWidget />);
      await waitFor(() => screen.getByText("empty"));
      expect(screen.getByText("empty")).toBeTruthy();
    });

    it("does not show a count badge when list is empty", async () => {
      render(<FollowUpsWidget />);
      await waitFor(() => screen.getByText("empty"));
      expect(screen.queryByText(/\(\d+\)/)).toBeNull();
    });
  });

  describe("populated state", () => {
    beforeEach(() => {
      const items = Array.from({ length: 7 }, (_, i) => makeFollowUp(i + 1));
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => items,
      });
    });

    it("shows only 5 items when fetch returns 7", async () => {
      render(<FollowUpsWidget />);
      await waitFor(() => screen.getByText(/Follow-up summary number 1/));
      const links = screen.getAllByRole("link");
      expect(links).toHaveLength(5);
    });

    it("shows count (7) in heading when fetch returns 7 items", async () => {
      render(<FollowUpsWidget />);
      await waitFor(() => screen.getByText(/\(7\)/));
      expect(screen.getByText(/title.*\(7\)|\(7\)/)).toBeTruthy();
    });
  });
});
