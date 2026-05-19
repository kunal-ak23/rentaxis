import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import RenewalCard from "../RenewalCard";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars) return `${key}:${JSON.stringify(vars)}`;
    return key;
  },
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const baseProps = {
  leaseId: "lease-1",
  unitNumber: "A-101",
  propertyNameEn: "Marina Towers",
  endDate: "2026-06-30",
  daysRemaining: 48,
  opportunityId: "opp-1",
  stage: "OPEN",
  intent: null,
  reminders: [],
};

describe("RenewalCard", () => {
  it("renders beyond-90 hint when opportunityId is null", () => {
    render(
      <RenewalCard
        {...baseProps}
        opportunityId={null}
      />
    );
    expect(screen.getByText("beyond90Hint")).toBeTruthy();
    // Should not render the property name line
    expect(screen.queryByText(/Marina Towers/)).toBeNull();
  });

  it("renders 'You've selected' and 2 change-buttons when intent is RENEW", () => {
    render(
      <RenewalCard
        {...baseProps}
        intent="RENEW"
      />
    );
    // status selected message is rendered
    expect(screen.getByText(/statusYouSelected/)).toBeTruthy();
    // changeYourMind label shown
    expect(screen.getByText("changeYourMind")).toBeTruthy();
    // Two change buttons for MOVE_OUT and DISCUSS
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(2);
    const labels = buttons.map(b => b.textContent);
    expect(labels).toContain("intent.MOVE_OUT");
    expect(labels).toContain("intent.DISCUSS");
  });

  it("renders 3 intent buttons when no intent; clicking RENEW fires onSetIntent", () => {
    const onSetIntent = vi.fn();
    render(
      <RenewalCard
        {...baseProps}
        intent={null}
        onSetIntent={onSetIntent}
      />
    );
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(3);

    // Click the RENEW button (first button renders "renew" key)
    const renewBtn = buttons.find(b => b.textContent === "renew");
    expect(renewBtn).toBeTruthy();
    fireEvent.click(renewBtn!);
    expect(onSetIntent).toHaveBeenCalledOnce();
    expect(onSetIntent).toHaveBeenCalledWith("RENEW");
  });
});
