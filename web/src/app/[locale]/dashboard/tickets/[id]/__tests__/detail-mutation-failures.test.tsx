import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Break round 2 silent-mutation sweep: on the ticket detail page a refused
// reply (P1 — and the typed reply was kept only by luck), rating (P2) or
// attachment delete (P2) did nothing visible. Each now says so, in the user's
// language, and keeps what the user typed / the row they tried to remove.

const TICKET_ID = "11111111-1111-1111-1111-111111111111";
const session = vi.hoisted(() => ({ role: "TENANT_ADMIN", locale: "en" as "en" | "ar" }));

vi.mock("next/navigation", () => ({ useParams: () => ({ id: TICKET_ID }) }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const en = (await import("../../../../../../../messages/en.json")).default;
    const ar = (await import("../../../../../../../messages/ar.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            const key = `${session.locale}:${namespace}`;
            if (!cache.has(key)) cache.set(key, createTranslator({ locale: session.locale, messages: session.locale === "ar" ? ar : en, namespace: namespace as never }));
            return cache.get(key)!;
        },
        useLocale: () => session.locale,
    };
});
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: session.role, id: "99999999-9999-9999-9999-999999999999" } } }),
}));
vi.mock("@/i18n/routing", () => ({ Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }));
vi.mock("@/components/ui/ImageLightbox", () => ({ ImageLightbox: () => null }));

import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import TicketDetailPage from "../page";

const baseTicket = {
    id: TICKET_ID, title: "Leaking tap", description: "", status: "OPEN", priority: "MEDIUM", category: "PLUMBING",
    propertyId: "p1", propertyName: "Tower A", unitId: "u1", unitNumber: "101", reporterName: "Renter", reportedBy: "r1",
    assignedTo: null, assigneeName: null, createdAt: "2026-08-01T00:00:00Z", updatedAt: "2026-08-01T00:00:00Z",
    estimatedResolutionHours: null, closureOtp: null, closableWithoutOtp: false, otpLocked: false,
    closeWithoutOtpReason: null, canReissueOtp: false, satisfactionRating: null, satisfactionComment: null, attachments: [],
};
let ticket = { ...baseTicket };

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

let mutationOk = false;
let calls: { url: string; method: string }[] = [];
const attachment = { id: "att-1", name: "quote.pdf", fileUrl: "/x/quote.pdf", fileType: "application/pdf", fileSize: 2048, uploadedAt: "2026-08-01T00:00:00Z" };

beforeEach(() => {
    session.role = "TENANT_ADMIN";
    session.locale = "en";
    ticket = { ...baseTicket };
    mutationOk = false;
    calls = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        calls.push({ url: u, method });
        if (method !== "GET") {
            return mutationOk ? jsonRes({}) : jsonRes({ error: true, message: "Internal error" }, false, 500);
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}/attachments`)) return jsonRes([attachment]);
        if (u.includes("/replies") || u.includes("/history") || u.includes("/assignees")) return jsonRes([]);
        if (u.endsWith(`/v1/tickets/${TICKET_ID}`)) return jsonRes(ticket);
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Ticket detail — refused mutations are shown", () => {
    it("keeps the typed reply and says it was not sent (P1)", async () => {
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        const box = screen.getByPlaceholderText(en.Tickets.replyPlaceholder) as HTMLTextAreaElement;
        fireEvent.change(box, { target: { value: "The plumber is booked for Monday" } });
        fireEvent.click(screen.getByRole("button", { name: en.Tickets.send }));

        expect(await screen.findByText(en.Tickets.replyFailed)).toBeTruthy();
        expect(box.value).toBe("The plumber is booked for Monday");
        expect(calls.some(c => c.url.endsWith("/replies") && c.method === "POST")).toBe(true);
    });

    it("clears the reply and shows no error once a send succeeds", async () => {
        mutationOk = true;
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        const box = screen.getByPlaceholderText(en.Tickets.replyPlaceholder) as HTMLTextAreaElement;
        fireEvent.change(box, { target: { value: "Done" } });
        fireEvent.click(screen.getByRole("button", { name: en.Tickets.send }));
        await waitFor(() => expect(box.value).toBe(""));
        expect(screen.queryByText(en.Tickets.replyFailed)).toBeNull();
    });

    it("says the rating was not saved, keeping the stars and comment (P2), in Arabic too", async () => {
        session.role = "RENTER";
        session.locale = "ar";
        ticket = { ...baseTicket, status: "CLOSED" };
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        const comment = screen.getByPlaceholderText(ar.Tickets.optionalComment) as HTMLTextAreaElement;
        fireEvent.change(comment, { target: { value: "سريع" } });
        // The fourth star.
        const stars = document.querySelectorAll("svg.lucide-star");
        fireEvent.click(stars[3].closest("button")!);
        fireEvent.click(screen.getByRole("button", { name: ar.Tickets.submitRating }));

        expect(await screen.findByText(ar.Tickets.ratingFailed)).toBeTruthy();
        expect(comment.value).toBe("سريع");
        expect(document.querySelectorAll("svg.lucide-star.fill-accent")).toHaveLength(4);
    });

    it("keeps the attachment and says it was not removed (P2)", async () => {
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        await waitFor(() => expect(document.querySelector("svg.lucide-trash2")).not.toBeNull());
        fireEvent.click(document.querySelector("svg.lucide-trash2")!.closest("button")!);

        expect(await screen.findByText(en.Tickets.attachmentDeleteFailed)).toBeTruthy();
        expect(document.querySelector("svg.lucide-trash2")).not.toBeNull();
        expect(calls.some(c => c.url.endsWith("/v1/tickets/attachments/att-1") && c.method === "DELETE")).toBe(true);
    });
});

// Break-it R3 portal3 F7: a failed attachment download used to do nothing at all.
describe("Ticket detail — a failed attachment download is shown", () => {
    it.each(["en", "ar"] as const)("says the file could not be downloaded (%s)", async (locale) => {
        session.role = "RENTER";
        session.locale = locale;
        const m = locale === "ar" ? ar : en;
        render(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        const buttons = await screen.findAllByRole("button", { name: m.Tickets.downloadAttachment });
        fireEvent.click(buttons[0]);
        expect(await screen.findByText(m.Tickets.attachmentDownloadFailed)).toBeTruthy();
        expect(calls.some(c => c.url.endsWith("/attachments/att-1/download"))).toBe(true);
    });
});

// Break-it R3 (users): a deleted assignee's open tickets go back to the queue with an UNASSIGNED
// history row whose stored note is English; the page says it in the user's language.
describe("Ticket detail — the UNASSIGNED history row", () => {
    it.each(["en", "ar"] as const)("is rendered from the message key (%s)", async (locale) => {
        session.locale = locale;
        const m = locale === "ar" ? ar : en;
        const history = [{
            id: "h1", action: "UNASSIGNED", fromStatus: "ASSIGNED", toStatus: "OPEN",
            notes: "Assignee's account was deleted; ticket returned to the unassigned queue",
            performedByName: "Admin", createdAt: "2026-09-01T00:00:00Z",
        }];
        const base = global.fetch;
        global.fetch = vi.fn(async (url: unknown, init?: RequestInit) =>
            String(url).endsWith("/history") ? jsonRes(history) : (base as typeof fetch)(url as string, init)) as unknown as typeof fetch;
        render(<TicketDetailPage />);
        expect(await screen.findByText(m.Tickets.history.unassigned)).toBeTruthy();
    });
});
