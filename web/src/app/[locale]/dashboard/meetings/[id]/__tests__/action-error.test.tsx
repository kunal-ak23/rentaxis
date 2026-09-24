import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// A failed meeting action (e.g. 400 "Cannot approve meeting in status: ...")
// must surface the backend's {message} body instead of silently doing nothing.

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "11111111-1111-1111-1111-111111111111" }),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "u1" } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => Object.assign((key: string) => key, { has: () => false }),
    useLocale: () => "en",
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

import MeetingDetailPage from "../page";

const MEETING_ID = "11111111-1111-1111-1111-111111111111";

const meeting = {
    id: MEETING_ID,
    type: "OFFICE_VISIT",
    status: "REQUESTED",
    purpose: "Discuss lease",
    title: "Lease discussion",
    notes: null,
    slotStart: "2026-08-10T10:00:00Z",
    slotEnd: "2026-08-10T10:30:00Z",
    hostUserId: null,
    hostName: null,
    requesterUserId: "r1",
    requesterName: "Renter",
    leaseId: null,
    leaseLabel: null,
    propertyId: null,
    propertyName: null,
    unitId: null,
    unitNumber: null,
    details: null,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
};

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({
        ok,
        status,
        json: async () => body,
        text: async () => JSON.stringify(body),
    }) as unknown as Response;

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.endsWith(`/v1/meetings/${MEETING_ID}/approve`) && init?.method === "PUT") {
            return jsonRes(
                { error: true, message: "Cannot approve meeting in status: CANCELLED", status: 400 },
                false,
                400
            );
        }
        if (u.endsWith(`/v1/meetings/${MEETING_ID}`)) {
            return jsonRes(meeting);
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("MeetingDetailPage actions", () => {
    it("surfaces the backend message when an action fails", async () => {
        render(<MeetingDetailPage />);

        fireEvent.click(await screen.findByText("approve"));

        expect(
            await screen.findByText("Cannot approve meeting in status: CANCELLED")
        ).toBeTruthy();
    });
});
