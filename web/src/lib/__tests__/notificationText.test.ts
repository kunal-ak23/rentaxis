import { describe, expect, it } from "vitest";
import { createTranslator } from "next-intl";

import ar from "../../../messages/ar.json";
import en from "../../../messages/en.json";
import { notificationText, timeAgo } from "../notificationText";

/**
 * #81: notifications are written in English by the server. With the key and
 * raw values it now stores beside the English, the sentence is rebuilt in the
 * viewer's language; rows without them keep their English.
 */

const tr = (messages: typeof en, locale: string, namespace: string) =>
    createTranslator({ locale, messages, namespace: namespace as never }) as unknown as Parameters<typeof notificationText>[1];

const arT = tr(ar as typeof en, "ar", "Notifications");
const enT = tr(en, "en", "Notifications");
const arMeetings = tr(ar as typeof en, "ar", "Meetings");

const FSI = "⁨";
const PDI = "⁩";
const bare = (s: string) => s.replace(/[⁨⁩]/g, "");

const row = (messageKey: string | null, params: Record<string, string> | null, title = "English title", message = "English body") =>
    ({ type: "X", title, message, messageKey, params });

describe("notificationText", () => {
    it("rebuilds a ticket assignment in Arabic, the title isolated", () => {
        const text = notificationText(row("TICKET_ASSIGNED", { ticketTitle: "Leaking tap", ticketRef: "TKT-26/14" }), arT, "ar");

        expect(text.title).toBe(ar.Notifications.messages.TICKET_ASSIGNED.title);
        expect(text.body).toBe(`التذكرة: ${FSI}Leaking tap${PDI}`);
    });

    it("groups the amount, names the cheque by number and pluralises the days", () => {
        const due = row("PAYMENT_DUE", { seq: "2", amount: "31500.00", days: "3", chequeNo: "700102" });

        expect(bare(notificationText(due, enT, "en").body)).toBe("Cheque 700102 of AED 31,500.00 is due in 3 days.");
        expect(bare(notificationText(due, arT, "ar").body)).toBe("الشيك رقم 700102 بقيمة 31,500.00 درهم مستحق خلال 3 أيام.");
        // One day, and no cheque number: the instalment position instead.
        const oneDay = row("PAYMENT_DUE", { seq: "2", amount: "500", days: "1" });
        expect(bare(notificationText(oneDay, enT, "en").body)).toBe("Instalment #2 of AED 500.00 is due in 1 day.");
        expect(bare(notificationText(oneDay, arT, "ar").body)).toBe("القسط رقم 2 بقيمة 500.00 درهم مستحق خلال يوم واحد.");
    });

    it("writes a meeting slot in UAE time and the viewer's language", () => {
        const text = notificationText(row("MEETING_REQUESTED_HOST", { slot: "2026-09-10T06:00:00Z" }), arT, "ar");
        // 06:00Z is 10:00 in Dubai.
        expect(text.body).toMatch(/^طُلب اجتماع في /);
        expect(text.body).toMatch(/10:00/);
        expect(text.body).not.toMatch(/[A-Za-z]/);
    });

    it("names a cancelled meeting by its purpose when it has no title", () => {
        const text = notificationText(
            row("MEETING_CANCELLED", { name: "Omar", purpose: "LEASE_RENEWAL", slot: "2026-09-10T06:00:00Z" }),
            arT, "ar", arMeetings);
        expect(bare(text.body)).toBe(`ألغى Omar الاجتماع: ${ar.Meetings.purposeLabel.LEASE_RENEWAL}`);
    });

    it("words a parking booking and localizes enum reasons", () => {
        const booking = row("BOOKING_APPROVED", { resourceType: "PARKING", resourceName: "P1" });
        expect(bare(notificationText(booking, arT, "ar").body)).toBe("تمت الموافقة على طلب حجزك لـ موقف السيارة P1.");

        const bounced = row("CHEQUE_RETURNED_REASON", { seq: "4", amount: "1000", reason: "SIGNATURE_MISMATCH" });
        expect(bare(notificationText(bounced, arT, "ar").body)).toContain("عدم تطابق التوقيع");

        const penalty = row("PENALTY_INCURRED_INSTALMENT", { amount: "500", seq: "3", reason: "CHEQUE_RETURN" });
        expect(bare(notificationText(penalty, enT, "en").body)).toContain("for instalment #3 (cheque return)");
    });

    it("uses the shorter sentence when a value is missing", () => {
        const text = notificationText(row("LISTING_AVAILABLE", null), arT, "ar");
        expect(text.body).toBe(ar.Notifications.messages.LISTING_AVAILABLE.bodyShort);
    });

    it("keeps the stored English for an older row, an unknown key, or a missing value with no short form", () => {
        expect(notificationText(row(null, null), arT, "ar")).toEqual({ title: "English title", body: "English body" });
        expect(notificationText(row("SOMETHING_NEW", { a: "1" }), arT, "ar").body).toBe("English body");
        expect(notificationText(row("TICKET_ASSIGNED", {}), arT, "ar").body).toBe("English body");
    });

    it("says how long ago in the viewer's language", () => {
        const now = new Date("2026-09-24T12:00:00Z");
        expect(timeAgo("2026-09-24T11:55:00Z", arT, "ar", now)).toBe("منذ 5 دقائق");
        expect(timeAgo("2026-09-24T10:00:00Z", arT, "ar", now)).toBe("منذ ساعتين");
        expect(timeAgo("2026-09-24T11:55:00Z", enT, "en", now)).toBe("5 min ago");
    });
});
