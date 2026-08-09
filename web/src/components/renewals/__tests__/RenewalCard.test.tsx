import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import RenewalCard from "../RenewalCard";

vi.mock("next-intl", () => {
    const translate = (key: string, vars?: Record<string, string | number>) => {
        if (!vars) return key;
        let out = key;
        for (const [k, v] of Object.entries(vars)) out = out.replaceAll(`{${k}}`, String(v));
        return out;
    };
    return {
        useTranslations: () => translate,
        useLocale: () => "en",
    };
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const base = {
    leaseId: "22222222-2222-2222-2222-222222222222",
    unitNumber: "A-101",
    propertyNameEn: "Marina Tower",
    endDate: "2026-09-01",
    daysRemaining: 20,
    opportunityId: "33333333-3333-3333-3333-333333333333",
    stage: "OPEN",
    intent: null,
    reminders: [],
};

describe("RenewalCard", () => {
    it("renders beyond-90 hint when opportunityId is null", () => {
        render(<RenewalCard {...base} opportunityId={null} />);
        expect(screen.getByText("beyond90Hint")).toBeTruthy();
        // Should not render the property name line
        expect(screen.queryByText(/Marina Tower/)).toBeNull();
    });

    it("renders 'You've selected' and 2 change-buttons when intent is RENEW", () => {
        render(<RenewalCard {...base} intent="RENEW" />);
        expect(screen.getByText(/statusYouSelected/)).toBeTruthy();
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
        render(<RenewalCard {...base} intent={null} onSetIntent={onSetIntent} />);
        const buttons = screen.getAllByRole("button");
        expect(buttons).toHaveLength(3);

        const renewBtn = buttons.find(b => b.textContent === "renew");
        expect(renewBtn).toBeTruthy();
        fireEvent.click(renewBtn!);
        expect(onSetIntent).toHaveBeenCalledOnce();
        expect(onSetIntent).toHaveBeenCalledWith("RENEW");
    });
});

describe("RenewalCard title null-safety", () => {
    it("joins property and unit when both are present", () => {
        render(<RenewalCard {...base} />);
        expect(screen.getByText("Marina Tower · A-101")).toBeTruthy();
    });

    it("shows only the non-null part when one is missing", () => {
        render(<RenewalCard {...base} propertyNameEn={null} />);
        expect(screen.getByText("A-101")).toBeTruthy();
    });

    it("falls back to unitFallback when both are null", () => {
        render(<RenewalCard {...base} unitNumber={null} propertyNameEn={null} />);
        expect(screen.getByText("unitFallback")).toBeTruthy();
    });
});
