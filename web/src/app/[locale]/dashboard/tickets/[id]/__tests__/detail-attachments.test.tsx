import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Break round 2 (portal2) F1, detail page: the add-attachment upload's result
// was never checked, so a refused file (e.g. over the backend's 10 MB cap)
// disappeared without a word; an empty file was stored as a 0-byte attachment.

const TICKET_ID = "11111111-1111-1111-1111-111111111111";

vi.mock("next/navigation", () => ({ useParams: () => ({ id: TICKET_ID }) }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            if (!cache.has(namespace)) cache.set(namespace, createTranslator({ locale: "en", messages, namespace: namespace as never }));
            return cache.get(namespace)!;
        },
        useLocale: () => "en",
    };
});
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "99999999-9999-9999-9999-999999999999" } } }),
}));
vi.mock("@/i18n/routing", () => ({ Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }));
vi.mock("@/components/ui/ImageLightbox", () => ({ ImageLightbox: () => null }));

import TicketDetailPage from "../page";

const ticket = {
    id: TICKET_ID, title: "Leaking tap", description: "", status: "OPEN", priority: "MEDIUM", category: "PLUMBING",
    propertyId: "p1", propertyName: "Tower A", unitId: "u1", unitNumber: "101", reporterName: "Renter", reportedBy: "r1",
    assignedTo: null, assigneeName: null, createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z",
    estimatedResolutionHours: null, closureOtp: null, closableWithoutOtp: false, otpLocked: false,
    closeWithoutOtpReason: null, canReissueOtp: false, satisfactionRating: null, satisfactionComment: null, attachments: [],
};

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

let uploadOk = true;
let uploads: string[] = [];

beforeEach(() => {
    uploadOk = true;
    uploads = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.startsWith("/api/upload")) {
            uploads.push(((init?.body as FormData).get("file") as File).name);
            expect(u).toBe(`/api/upload?path=/api/v1/tickets/${TICKET_ID}/attachments`);
            return uploadOk ? jsonRes({ id: "a1" }) : jsonRes({ error: true, message: "File is larger than 10 MB" }, false, 400);
        }
        if (u.includes("/replies") || u.includes("/attachments") || u.includes("/history") || u.includes("/assignees")) return jsonRes([]);
        if (u.endsWith(`/v1/tickets/${TICKET_ID}`)) return jsonRes(ticket);
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function pick(input: HTMLInputElement, files: File[]) {
    let selected = [...files];
    Object.defineProperty(input, "files", {
        configurable: true,
        get: () => Object.assign([...selected], { item: (i: number) => selected[i] ?? null }),
    });
    Object.defineProperty(input, "value", { configurable: true, get: () => "", set: (v: string) => { if (v === "") selected = []; } });
    fireEvent.change(input);
}

describe("Ticket detail — add attachment", () => {
    it("uploads a valid file and shows no error", async () => {
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        pick(document.querySelector('input[type="file"]') as HTMLInputElement, [new File(["x"], "tap.jpg", { type: "image/jpeg" })]);
        await waitFor(() => expect(uploads).toEqual(["tap.jpg"]));
        expect(screen.queryByTestId("ticket-attachment-errors")).toBeNull();
    });

    it("names a file the server refused", async () => {
        uploadOk = false;
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        pick(document.querySelector('input[type="file"]') as HTMLInputElement, [new File(["x"], "refused.jpg", { type: "image/jpeg" })]);
        const errors = await screen.findByTestId("ticket-attachment-errors");
        expect(errors.textContent).toContain("refused.jpg");
        expect(errors.textContent).toMatch(/could not be uploaded/);
    });

    it("never sends an empty file, and says why", async () => {
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        pick(document.querySelector('input[type="file"]') as HTMLInputElement, [new File([], "empty.jpg", { type: "image/jpeg" })]);
        const errors = await screen.findByTestId("ticket-attachment-errors");
        expect(errors.textContent).toContain("empty.jpg");
        expect(uploads).toEqual([]);
    });
});
