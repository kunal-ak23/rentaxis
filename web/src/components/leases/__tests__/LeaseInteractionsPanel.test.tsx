import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import LeaseInteractionsPanel from "../LeaseInteractionsPanel";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars) return `${key}:${JSON.stringify(vars)}`;
    return key;
  },
}));

const interactions = [
  {
    id: "i1",
    leaseId: "L1",
    opportunityId: null,
    type: "SYSTEM_INTENT",
    direction: "INBOUND",
    occurredAt: "2026-05-01T10:00:00Z",
    summary: "Renter accepted the lease offer",
    outcome: "POSITIVE",
    followUpDate: null,
    createdBy: "system",
    createdByName: "System",
    createdAt: "2026-05-01T10:00:00Z",
  },
  {
    id: "i2",
    leaseId: "L1",
    opportunityId: null,
    type: "CALL",
    direction: "OUTBOUND",
    occurredAt: "2026-05-02T11:00:00Z",
    summary: "Called renter to discuss renewal terms",
    outcome: "NEUTRAL",
    followUpDate: "2026-05-10",
    createdBy: "u1",
    createdByName: "Jane Manager",
    createdAt: "2026-05-02T11:00:00Z",
  },
];

beforeEach(() => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ content: interactions }),
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("LeaseInteractionsPanel", () => {
  it("renders both interactions after fetch resolves", async () => {
    render(<LeaseInteractionsPanel leaseId="L1" />);

    await waitFor(() => screen.getByText("Renter accepted the lease offer"));

    expect(screen.getByText("Called renter to discuss renewal terms")).toBeTruthy();
  });

  it("SYSTEM_INTENT item has border-blue-400 and opacity-80 classes", async () => {
    render(<LeaseInteractionsPanel leaseId="L1" />);

    await waitFor(() => screen.getByText("Renter accepted the lease offer"));

    const systemItem = screen.getByText("Renter accepted the lease offer").closest("li");
    expect(systemItem?.className).toContain("border-blue-400");
    expect(systemItem?.className).toContain("opacity-80");
  });

  it("CALL item has border-primary class", async () => {
    render(<LeaseInteractionsPanel leaseId="L1" />);

    await waitFor(() => screen.getByText("Called renter to discuss renewal terms"));

    const callItem = screen.getByText("Called renter to discuss renewal terms").closest("li");
    expect(callItem?.className).toContain("border-primary");
    expect(callItem?.className).not.toContain("border-blue-400");
  });

  it("Log interaction button is visible", async () => {
    render(<LeaseInteractionsPanel leaseId="L1" />);

    await waitFor(() => screen.getByText("Renter accepted the lease offer"));

    expect(screen.getByRole("button", { name: /logInteraction/ })).toBeTruthy();
  });
});
