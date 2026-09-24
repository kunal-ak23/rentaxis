import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../../../../../../messages/en.json";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Covers three audited contract bugs on the ticket detail page:
// 1. The "Assign To..." picker lists GET /v1/tickets/{id}/assignees — the people
//    the assign call accepts for THIS ticket's building — not the org-wide
//    /api/admin/users (admin-only, and it offered other buildings' managers).
// 2. The ETA row must read the DTO's estimatedResolutionHours field
//    (MaintenanceTicketDTO), not a non-existent estimatedHours.
// 3. Failed status actions (400 with a {message} body) must surface the
//    backend message instead of silently doing nothing.
// 4. Status and ETA actions must remain disabled while their request is in
//    flight, preventing duplicate transitions and estimates.

const sessionUser = vi.hoisted(() => ({ role: "TENANT_ADMIN" }));

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "11111111-1111-1111-1111-111111111111" }),
}));
vi.mock("next-intl", async () => {
    // The real English catalog, so labels, plurals and enum names render as
    // a user sees them.
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../../messages/en.json")).default;
    // One translator per namespace, stable across renders like the real hook.
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
    useSession: () => ({ data: { user: { role: sessionUser.role, id: "99999999-9999-9999-9999-999999999999" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock("@/components/ui/ImageLightbox", () => ({ ImageLightbox: () => null }));

import TicketDetailPage from "../page";

const TICKET_ID = "11111111-1111-1111-1111-111111111111";

const baseTicket = {
    id: TICKET_ID,
    title: "Leaking tap",
    description: "Kitchen tap leaks",
    status: "IN_PROGRESS",
    priority: "MEDIUM",
    category: "PLUMBING",
    propertyId: "p1",
    propertyName: "Tower A",
    unitId: "u1",
    unitNumber: "101",
    reporterName: "Renter",
    reportedBy: "r1",
    assignedTo: null,
    assigneeName: null,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    estimatedResolutionHours: null as number | null,
    closureOtp: null,
    closableWithoutOtp: false,
    otpLocked: false,
    closeWithoutOtpReason: null as "OTP_OFF" | "NO_RENTER" | "LOCKED" | null,
    canReissueOtp: false,
    satisfactionRating: null,
    satisfactionComment: null,
    attachments: [],
};

let ticket: typeof baseTicket;
let statusActionResponse: { ok: boolean; status: number; body: unknown };
let statusActionPending: Promise<Response> | null;
let estimateActionPending: Promise<Response> | null;
let reissueResponse: { ok: boolean; status: number; body: unknown };

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({
        ok,
        status,
        json: async () => body,
        text: async () => JSON.stringify(body),
    }) as unknown as Response;

beforeEach(() => {
    ticket = { ...baseTicket };
    statusActionResponse = { ok: true, status: 200, body: {} };
    statusActionPending = null;
    estimateActionPending = null;
    reissueResponse = { ok: true, status: 200, body: {} };
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/admin/users")) {
            return jsonRes([{ id: "s9", name: "PM Other Building", email: "pm9@x.com", role: "PROPERTY_MANAGER" }]);
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}/assignees`)) {
            return jsonRes([
                { id: "a1", name: "Admin One", role: "TENANT_ADMIN" },
                { id: "s1", name: "PM This Building", role: "PROPERTY_MANAGER" },
            ]);
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}/assign`)) {
            return jsonRes({ ...ticket, status: "ASSIGNED", assignedTo: "s1", assigneeName: "PM This Building" });
        }
        if (u.includes("/replies") || u.includes("/attachments") || u.includes("/history")) {
            return jsonRes([]);
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}/status`)) {
            if (statusActionPending) return statusActionPending;
            return jsonRes(statusActionResponse.body, statusActionResponse.ok, statusActionResponse.status);
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}/closure-otp`)) {
            return jsonRes(reissueResponse.body, reissueResponse.ok, reissueResponse.status);
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}/estimate`) && estimateActionPending) {
            return estimateActionPending;
        }
        if (u.endsWith(`/v1/tickets/${TICKET_ID}`)) {
            return jsonRes(ticket);
        }
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("TicketDetailPage API contract", () => {
    it("never requests the org-wide /api/admin/users list", async () => {
        for (const role of ["PROPERTY_MANAGER", "TENANT_ADMIN"]) {
            sessionUser.role = role;
            ticket.status = "OPEN";
            render(<TicketDetailPage />);
            expect(await screen.findByText("Assign To...")).toBeTruthy();
            cleanup();
        }
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.map((c) => String(c[0]));
        expect(calls.some((u) => u.includes("/admin/users"))).toBe(false);
    });

    it("offers exactly the ticket's eligible assignees and assigns the one picked", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "OPEN";
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText("Assign To..."));
        expect(screen.getByText("Admin One")).toBeTruthy();
        expect(screen.getByText("PM This Building")).toBeTruthy();
        expect(screen.queryByText("PM Other Building")).toBeNull();

        await act(async () => {
            fireEvent.click(screen.getByText("PM This Building"));
        });
        const assign = (global.fetch as ReturnType<typeof vi.fn>).mock.calls
            .find((c) => String(c[0]).endsWith(`/v1/tickets/${TICKET_ID}/assign`));
        expect(assign).toBeTruthy();
        expect(JSON.parse(String((assign![1] as RequestInit).body))).toEqual({ assignTo: "s1" });
    });

    it("renders the ETA row from the DTO's estimatedResolutionHours", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.estimatedResolutionHours = 5;
        render(<TicketDetailPage />);

        expect(await screen.findByText("5 hours")).toBeTruthy();
    });

    it("labels the reported-on row through the translation key, not hard-coded English", async () => {
        sessionUser.role = "TENANT_ADMIN";
        (ticket as typeof baseTicket & { reportedDate: string }).reportedDate = "2026-09-11";
        render(<TicketDetailPage />);

        await screen.findByText("Leaking tap");
        // The mocked useTranslations returns the key itself — this fails if the
        // label ever reverts to a literal "Reported On" string.
        expect(screen.getByText(en.Tickets.reportedOn)).toBeTruthy();
    });

    it("surfaces the backend message when a status action fails", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = "CLOSED";
        statusActionResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "Invalid status transition: CLOSED -> REOPENED", status: 400 },
        };
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText("Reopen Ticket"));

        expect(
            await screen.findByText("Invalid status transition: CLOSED -> REOPENED")
        ).toBeTruthy();
    });

    it.each([
        ["ASSIGNED", "Start Work"],
        ["IN_PROGRESS", "Mark Resolved"],
    ])("disables %s status actions while the request is pending", async (status, label) => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = status;
        let resolveAction!: (response: Response) => void;
        statusActionPending = new Promise<Response>((resolve) => { resolveAction = resolve; });
        render(<TicketDetailPage />);

        const button = await screen.findByText(label);
        fireEvent.click(button);

        expect((button as HTMLButtonElement).disabled).toBe(true);
        await act(async () => {
            resolveAction(jsonRes({ ...ticket, status }));
            await statusActionPending;
        });
    });

    it("disables Set ETA while the estimate request is pending", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = "ASSIGNED";
        let resolveAction!: (response: Response) => void;
        estimateActionPending = new Promise<Response>((resolve) => { resolveAction = resolve; });
        render(<TicketDetailPage />);

        fireEvent.change(await screen.findByPlaceholderText("Hours"), { target: { value: "4" } });
        const button = screen.getByText("Set ETA");
        fireEvent.click(button);

        expect((button as HTMLButtonElement).disabled).toBe(true);
        await act(async () => {
            resolveAction(jsonRes({ ...ticket, estimatedResolutionHours: 4 }));
            await estimateActionPending;
        });
    });
});

// PR #342: a RESOLVED ticket nobody can confirm with a code is closed through
// the status route, and staff can re-issue a closure code where a renter can
// receive one. The page follows the DTO's closableWithoutOtp / otpLocked flags.
describe("TicketDetailPage closing a resolved ticket", () => {
    const calls = () =>
        (global.fetch as ReturnType<typeof vi.fn>).mock.calls.map((c) => ({
            url: String(c[0]),
            init: c[1] as RequestInit | undefined,
        }));

    it("offers Close ticket with a confirm when no renter can confirm, and closes through the status route", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "RESOLVED";
        ticket.closableWithoutOtp = true;
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText(en.Tickets.closeTicket));
        // Nothing is sent until the confirm, which explains the closure.
        expect(calls().some((c) => c.url.endsWith("/status"))).toBe(false);
        expect(screen.getByText(en.Tickets.closeWithoutOtpNoRenter)).toBeTruthy();
        // No code to enter or re-send when nobody can confirm.
        expect(screen.queryByPlaceholderText("6-digit OTP")).toBeNull();
        expect(screen.queryByText(en.Tickets.reissueOtp)).toBeNull();

        await act(async () => {
            fireEvent.click(screen.getByText(en.Tickets.closeTicketConfirm));
        });

        const statusCall = calls().find((c) => c.url.endsWith(`/v1/tickets/${TICKET_ID}/status`));
        expect(statusCall?.init?.method).toBe("PUT");
        expect(JSON.parse(String(statusCall?.init?.body))).toEqual({ status: "CLOSED" });
    });

    it("cancelling the confirm sends nothing", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = "RESOLVED";
        ticket.closableWithoutOtp = true;
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText(en.Tickets.closeTicket));
        fireEvent.click(screen.getByText(en.Tickets.cancel));

        expect(screen.queryByText(en.Tickets.closeWithoutOtpNoRenter)).toBeNull();
        expect(calls().some((c) => c.url.endsWith("/status"))).toBe(false);
    });

    it("explains the lockout in the confirm when an admin closes a locked ticket", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = "RESOLVED";
        ticket.closableWithoutOtp = true;
        ticket.otpLocked = true;
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText(en.Tickets.closeTicket));
        expect(screen.getByText(en.Tickets.closeWithoutOtpLocked)).toBeTruthy();
        expect(screen.queryByText(en.Tickets.reissueOtp)).toBeNull();
    });

    it("shows nothing new when the renter can confirm: OTP closure plus a re-send", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "RESOLVED";
        ticket.canReissueOtp = true;
        render(<TicketDetailPage />);

        expect(await screen.findByPlaceholderText("6-digit OTP")).toBeTruthy();
        expect(screen.queryByText(en.Tickets.closeTicket)).toBeNull();
        expect(screen.getByText(en.Tickets.reissueOtp)).toBeTruthy();
    });

    it("does not offer Close ticket on a ticket that is not resolved", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = "IN_PROGRESS";
        ticket.closableWithoutOtp = true;
        render(<TicketDetailPage />);

        await screen.findByText("Mark Resolved");
        expect(screen.queryByText(en.Tickets.closeTicket)).toBeNull();
    });

    it("tells a property manager a locked ticket needs an admin, with no close or re-send", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "RESOLVED";
        ticket.otpLocked = true;
        render(<TicketDetailPage />);

        expect(await screen.findByText(en.Tickets.otpLockedStaffHint)).toBeTruthy();
        expect(screen.queryByText(en.Tickets.closeTicket)).toBeNull();
        expect(screen.queryByText(en.Tickets.reissueOtp)).toBeNull();
        expect(screen.queryByPlaceholderText("6-digit OTP")).toBeNull();
    });

    it("re-sends a closure code with POST and confirms it was sent", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "RESOLVED";
        ticket.canReissueOtp = true;
        render(<TicketDetailPage />);

        const resend = await screen.findByText(en.Tickets.reissueOtp);
        await act(async () => {
            fireEvent.click(resend);
        });

        const reissue = calls().find((c) => c.url.endsWith(`/v1/tickets/${TICKET_ID}/closure-otp`));
        expect(reissue?.init?.method).toBe("POST");
        expect(await screen.findByText(en.Tickets.reissueOtpSent)).toBeTruthy();
    });

    it("surfaces the 3-per-24-hours cap when re-sending is refused", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "RESOLVED";
        ticket.canReissueOtp = true;
        reissueResponse = {
            ok: false,
            status: 400,
            body: {
                error: true,
                message: "A closure OTP can be re-issued at most 3 times in 24 hours. Try again later.",
                status: 400,
            },
        };
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText(en.Tickets.reissueOtp));

        expect(
            await screen.findByText("A closure OTP can be re-issued at most 3 times in 24 hours. Try again later.")
        ).toBeTruthy();
        expect(screen.queryByText(en.Tickets.reissueOtpSent)).toBeNull();
    });

    // PR #342 review r3 M1: OTP switched off for the organisation is its own reason.
    it("says closure codes are off for the organisation when that is why it closes without one", async () => {
        sessionUser.role = "TENANT_ADMIN";
        ticket.status = "RESOLVED";
        ticket.closableWithoutOtp = true;
        ticket.closeWithoutOtpReason = "OTP_OFF";
        render(<TicketDetailPage />);

        fireEvent.click(await screen.findByText(en.Tickets.closeTicket));
        expect(screen.getByText(en.Tickets.closeWithoutOtpOtpOff)).toBeTruthy();
        expect(screen.queryByText(en.Tickets.closeWithoutOtpNoRenter)).toBeNull();
        expect(screen.queryByText(en.Tickets.reissueOtp)).toBeNull();
    });

    // PR #342 review r3 M5: a PM outside the property's scope gets canReissueOtp false.
    it("hides Send a new closure code when the DTO says this caller cannot re-issue", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        ticket.status = "RESOLVED";
        ticket.canReissueOtp = false;
        render(<TicketDetailPage />);

        expect(await screen.findByPlaceholderText("6-digit OTP")).toBeTruthy();
        expect(screen.queryByText(en.Tickets.reissueOtp)).toBeNull();
    });
});
