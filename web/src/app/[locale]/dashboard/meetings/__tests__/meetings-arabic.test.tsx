import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the meetings page's filters, table and FullCalendar stayed English
 * under /ar, and the calendar laid out LTR with English month names.
 */

const calendarProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }));

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "me-1" } } }),
}));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "m-1" }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
// FullCalendar itself does not lay out in jsdom; capture what the page hands it.
vi.mock("next/dynamic", () => ({
    default: () => (props: Record<string, unknown>) => {
        calendarProps.current = props;
        return <div data-testid="calendar" />;
    },
}));
vi.mock("../CreateMeetingModal", () => ({ default: () => null }));

import MeetingsPage from "../page";
import MeetingDetailPage from "../[id]/page";

const DATA = ["Tower A", "G-01", "Omar PM", "Rajesh Kumar", "RNT-26/3"];

const meeting = {
    id: "m-1", title: null, purpose: "LEASE_RENEWAL", type: "PROPERTY_VISIT", status: "NO_SHOW",
    slotStart: "2026-09-10T06:00:00Z", slotEnd: "2026-09-10T06:30:00Z",
    hostName: "Omar PM", requesterName: "Rajesh Kumar", requesterUserId: "r-1",
    propertyId: "p1", propertyName: "Tower A", unitId: "u1", unitNumber: "G-01",
    leaseId: "l1", leaseLabel: "RNT-26/3", notes: null, createdAt: "2026-09-01T00:00:00Z",
    details: { detailType: "LEASE_RENEWAL", proposedStartDate: "2026-10-01", proposedEndDate: "2027-10-01", proposedRentAmount: 60000 },
};

beforeEach(() => {
    calendarProps.current = null;
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        const body = u.includes("/v1/meetings/m-1") ? meeting : [meeting];
        return { ok: true, status: 200, json: async () => body } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const renderAr = (node: React.ReactNode) =>
    render(<NextIntlClientProvider locale="ar" messages={ar}>{node}</NextIntlClientProvider>);

describe("meetings in Arabic", () => {
    it("hands FullCalendar the Arabic locale, RTL direction and Arabic button text", async () => {
        renderAr(<MeetingsPage />);
        await screen.findByTestId("calendar");

        const props = calendarProps.current!;
        expect(props.locale).toBe("ar");
        expect(props.direction).toBe("rtl");
        expect((props.locales as { code: string }[]).map((l) => l.code)).toContain("ar");
        expect(props.buttonText).toEqual({
            today: ar.Meetings.calendarToday, month: ar.Meetings.calendarMonth,
            week: ar.Meetings.calendarWeek, day: ar.Meetings.calendarDay,
        });
        expect((props.events as { title: string }[])[0].title).toBe(ar.Meetings.purposeLabel.LEASE_RENEWAL);
    });

    it("filters and the list view carry no English", async () => {
        const { container } = renderAr(<MeetingsPage />);
        await screen.findByTestId("calendar");
        fireEvent.click(screen.getByText(ar.Meetings.list));

        expect(await screen.findByText(ar.Meetings.status.NO_SHOW, { selector: "span" })).toBeInTheDocument();
        expect(screen.getByText(ar.Meetings.allPurposes)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("the detail page carries no English", async () => {
        const { container } = renderAr(<MeetingDetailPage />);
        await screen.findByText(ar.Meetings.meetingDetails);

        expect(screen.getByText(ar.Meetings.leaseRenewalProposal)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });
});
