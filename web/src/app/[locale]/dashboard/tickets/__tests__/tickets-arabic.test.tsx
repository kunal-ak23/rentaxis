import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the tickets list, detail and reports pages were English end to end
 * under /ar (title, filters, statuses, priorities, columns, badges, actions).
 * Each page is rendered with the real Arabic catalog and scanned for Latin
 * words that are not fixture data.
 */

const session = vi.hoisted(() => ({ role: "TENANT_ADMIN" }));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: session.role, id: "me-1" } } }),
}));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "t-1" }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/ui/ImageLightbox", () => ({ ImageLightbox: () => null }));

import TicketsPage from "../page";
import TicketDetailPage from "../[id]/page";
import TicketReportsPage from "../reports/page";

const DATA = ["TKT-26/14", "TKT-26/15", "Leaking tap", "Kitchen tap leaks", "Tower A", "101", "Rajesh Kumar", "Sara Admin", "Omar PM", "KB"];

const ticket = {
    id: "t-1", reference: "TKT-26/14", title: "Leaking tap", description: "Kitchen tap leaks",
    status: "IN_PROGRESS", priority: "URGENT", category: "PEST_CONTROL",
    propertyId: "p1", propertyName: "Tower A", unitId: "u1", unitNumber: "101",
    reporterName: "Rajesh Kumar", reportedBy: "r1", assignedTo: "s1", assigneeName: "Omar PM",
    onBehalfOf: null, reportedDate: "2026-09-01", createdAt: "2026-09-01T08:00:00Z", updatedAt: "2026-09-02T08:00:00Z",
    estimatedResolutionHours: 5, closureOtp: null, closableWithoutOtp: false, otpLocked: false,
    closeWithoutOtpReason: null, canReissueOtp: false, satisfactionRating: 4, satisfactionComment: null,
    attachments: [],
};

let current: typeof ticket;

const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;

beforeEach(() => {
    session.role = "TENANT_ADMIN";
    current = { ...ticket };
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.endsWith("/v1/tickets")) return json([current, { ...current, id: "t-2", reference: "TKT-26/15", status: "REOPENED", priority: "LOW", category: "HVAC" }]);
        if (u.endsWith("/v1/tickets/reports")) {
            return json({
                totalTickets: 2, openCount: 1, resolvedCount: 1, closedCount: 0, avgResolutionHours: 3.5,
                avgSatisfaction: 4.2, overdueCount: 1,
                ticketsByCategory: { PLUMBING: 1, PEST_CONTROL: 1 }, ticketsByPriority: { URGENT: 1, LOW: 1 },
            });
        }
        if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
        if (u.endsWith("/v1/units")) return json([{ id: "u1", unitNumber: "101", property: { id: "p1" } }]);
        if (u.endsWith("/v1/renters")) return json([{ id: "ren-1", nameEn: "Rajesh Kumar", nameAr: null, phone: null }]);
        if (u.endsWith("/assignees")) return json([{ id: "s1", name: "Omar PM", role: "PROPERTY_MANAGER" }]);
        if (u.endsWith("/history")) {
            return json([
                { id: "h1", action: "STATUS_CHANGED", fromStatus: "ASSIGNED", toStatus: "IN_PROGRESS", performedByName: "Omar PM",
                  notes: "Status changed: ASSIGNED → IN_PROGRESS", createdAt: "2026-09-02T08:00:00Z" },
                { id: "h2", action: "ASSIGNED", fromStatus: "OPEN", toStatus: "ASSIGNED", performedByName: null,
                  notes: "Ticket assigned to Omar PM", createdAt: "2026-09-01T09:00:00Z" },
            ]);
        }
        if (u.includes("/replies") || u.includes("/attachments")) return json([]);
        if (u.endsWith("/v1/tickets/t-1")) return json(current);
        return json([]);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const renderAr = (node: React.ReactNode) =>
    render(<NextIntlClientProvider locale="ar" messages={ar}>{node}</NextIntlClientProvider>);

describe("tickets pages in Arabic", () => {
    it("list: title, filters, columns and badges carry no English", async () => {
        const { container } = renderAr(<TicketsPage />);
        await screen.findByText("TKT-26/14");

        expect(screen.getByText(ar.Tickets.title)).toBeInTheDocument();
        expect(screen.getAllByText(ar.Tickets.priority.URGENT).length).toBeGreaterThan(0);
        expect(screen.getAllByText(ar.Tickets.status.REOPENED).length).toBeGreaterThan(0);
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("create modal carries no English", async () => {
        const { container } = renderAr(<TicketsPage />);
        await screen.findByText("TKT-26/14");
        fireEvent.click(screen.getAllByText(ar.Tickets.createTicket)[0]);
        expect(screen.getByText(ar.Tickets.category.PEST_CONTROL)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("detail: labels, actions and the history log carry no English", async () => {
        const { container } = renderAr(<TicketDetailPage />);
        await screen.findByText("Leaking tap");
        await screen.findByText(ar.Tickets.markResolved);

        expect(screen.getByText(ar.Tickets.setEstimatedHours)).toBeInTheDocument();
        // History rebuilt from its structured fields, the name isolated.
        expect(await screen.findByText(`تغيّرت الحالة من ${ar.Tickets.status.ASSIGNED} إلى ${ar.Tickets.status.IN_PROGRESS}`)).toBeInTheDocument();
        expect(screen.getByText(ar.Tickets.history.assigned.replace("{name}", "⁨Omar PM⁩"))).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("detail: a resolved ticket's OTP and reopen controls carry no English", async () => {
        current = { ...ticket, status: "RESOLVED", canReissueOtp: true };
        const { container } = renderAr(<TicketDetailPage />);
        await screen.findByText(ar.Tickets.closeWithOtp);
        expect(screen.getByText(ar.Tickets.reopen)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("reports: cards and breakdowns carry no English", async () => {
        const { container } = renderAr(<TicketReportsPage />);
        await screen.findByText(ar.Tickets.reportsTitle);
        expect(screen.getByText(ar.Tickets.category.PEST_CONTROL)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });
});
