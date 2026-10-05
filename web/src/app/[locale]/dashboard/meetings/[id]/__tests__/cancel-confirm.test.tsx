import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

// Tutorial 23: Cancel acted on one click with no reason. It now asks first, takes
// an optional reason, and the cancelled meeting says who, when and why.

const ID = "22222222-2222-2222-2222-222222222222";
vi.mock("next/navigation", () => ({ useParams: () => ({ id: ID }) }));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "u1" } } }),
}));
const translate = vi.hoisted(() => Object.assign(
    (key: string, vars?: Record<string, string | number>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
    { has: () => false },
));
vi.mock("next-intl", () => ({ useTranslations: () => translate, useLocale: () => "en" }));
vi.mock("@/i18n/routing", () => ({ Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }));

import MeetingDetailPage from "../page";

const base = {
    id: ID, type: "OFFICE_VISIT", status: "APPROVED", purpose: "OTHER", title: "Key handover", notes: null,
    slotStart: "2026-10-10T06:00:00Z", slotEnd: "2026-10-10T06:30:00Z", hostUserId: "u1", hostName: "Admin",
    requesterUserId: "r1", requesterName: "Layla", leaseId: null, leaseLabel: null, propertyId: null,
    propertyName: null, unitId: null, unitNumber: null, details: null,
    createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
};
let api: ReturnType<typeof fetchRouter>;
let current: Record<string, unknown>;

beforeEach(() => {
    current = { ...base };
    api = fetchRouter();
    api.on("GET", `/v1/meetings/${ID}`, () => ({ body: current }));
    api.on("PUT", `/v1/meetings/${ID}/cancel`, call => {
        const reason = (call.body as { reason?: string } | undefined)?.reason ?? null;
        current = { ...base, status: "CANCELLED", cancellationReason: reason, cancelledByName: "Admin",
            cancelledAt: "2026-10-05T10:00:00Z" };
        return { body: current };
    });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Cancelling a meeting", () => {
    it("asks first: one click on Cancel cancels nothing", async () => {
        render(<MeetingDetailPage />);
        fireEvent.click(await screen.findByTestId("meeting-cancel"));
        expect(await screen.findByTestId("meeting-cancel-reason")).toBeTruthy();
        expect(api.callsTo("PUT", "/cancel")).toHaveLength(0);
    });

    it("sends the typed reason and shows who cancelled, when and why", async () => {
        render(<MeetingDetailPage />);
        fireEvent.click(await screen.findByTestId("meeting-cancel"));
        fireEvent.change(await screen.findByTestId("meeting-cancel-reason"), { target: { value: "  Office closed  " } });
        fireEvent.click(screen.getByTestId("meeting-cancel-confirm"));
        await waitFor(() => expect(api.callsTo("PUT", "/cancel")).toHaveLength(1));
        expect(api.callsTo("PUT", "/cancel")[0].body).toEqual({ reason: "Office closed" });
        const card = await screen.findByTestId("meeting-cancellation");
        expect(card.textContent).toContain("Office closed");
        expect(card.textContent).toContain("Admin");
    });

    it("a reason is optional", async () => {
        render(<MeetingDetailPage />);
        fireEvent.click(await screen.findByTestId("meeting-cancel"));
        fireEvent.click(await screen.findByTestId("meeting-cancel-confirm"));
        await waitFor(() => expect(api.callsTo("PUT", "/cancel")).toHaveLength(1));
        expect(api.callsTo("PUT", "/cancel")[0].body).toEqual({});
        expect((await screen.findByTestId("meeting-cancellation")).textContent).toContain("cancellation.noReason");
    });
});
