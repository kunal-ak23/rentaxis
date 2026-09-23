import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import IntlMessageFormat from "intl-messageformat";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";
import CreateMeetingModal from "../CreateMeetingModal";

/**
 * #69: the create-meeting wizard's labels were hard-coded English, so the
 * Arabic renter saw "Preferred Date *" and "Property Manager *" inside an RTL
 * page. Rendered here with the real Arabic catalogue.
 */

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/proxy/v1/leases/my-leases")) {
            return { ok: true, status: 200, json: async () => [
                { id: "l1", propertyId: "p1", propertyName: "Marina Tower", unitIdentifier: "101", status: "ACTIVE", endDate: "2027-01-01" },
            ] } as unknown as Response;
        }
        if (url.startsWith("/api/proxy/v1/meetings/default-host")) {
            return { ok: true, status: 200, json: async () => ({ userId: "host-1" }) } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("CreateMeetingModal under ar (#69)", () => {
    it("renders the wizard in Arabic through to the date step", async () => {
        render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <CreateMeetingModal isOpen onClose={() => {}} onSuccess={() => {}} session={{ user: { role: "RENTER" } }} />
            </NextIntlClientProvider>,
        );
        expect(screen.getByText(ar.Meetings.create.typeQuestion)).toBeTruthy();
        expect(screen.getByText(ar.Meetings.create.officeVisitDesc)).toBeTruthy();

        fireEvent.click(screen.getByText(ar.Meetings.officeVisit));
        fireEvent.click(screen.getByRole("button", { name: new RegExp(ar.Meetings.create.next) }));
        await screen.findByText(`${ar.Meetings.create.relatedLease} *`);
        const [purpose, lease] = screen.getAllByRole("combobox");
        fireEvent.change(purpose, { target: { value: "OTHER" } });
        fireEvent.change(lease, { target: { value: "l1" } });
        fireEvent.click(screen.getByRole("button", { name: new RegExp(ar.Meetings.create.next) }));

        await screen.findByText(`${ar.Meetings.create.preferredDate} *`);
        expect(document.body.textContent).not.toMatch(/Preferred Date|Property Manager|Next|Back|Step \d/);
    });

    it("formats the new ICU messages in both locales", () => {
        for (const [locale, messages] of [["en", en], ["ar", ar]] as const) {
            const c = messages.Meetings.create;
            for (const n of [0, 1, 2, 3, 11, 12]) {
                const out = new IntlMessageFormat(c.durationMonths, locale).format({ n });
                expect(String(out)).toMatch(/\S/);
            }
            expect(String(new IntlMessageFormat(c.stepOf, locale).format({ step: 3, total: 5, label: "x" }))).toContain("3");
        }
        expect(String(new IntlMessageFormat(en.Meetings.create.durationMonths, "en").format({ n: 1 }))).toBe("1 month");
        expect(String(new IntlMessageFormat(en.Meetings.create.durationMonths, "en").format({ n: 12 }))).toBe("12 months");
    });
});
