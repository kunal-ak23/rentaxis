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

    it("carries a meeting's free-text cancellation reason as typed", () => {
        const text = notificationText(
            row("MEETING_CANCELLED_REASON", { name: "Omar", title: "Key handover", note: "Office closed" }),
            arT, "ar", arMeetings);
        expect(bare(text.body)).toBe("ألغى Omar الاجتماع: Key handover. السبب: Office closed");
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

describe("promotion ad notifications (promoter role)", () => {
    const submitted = row("PROMO_AD_SUBMITTED", { promoterName: "Pat Promoter", adTitle: "Wash and fold", businessName: "Sparkle Laundry", adCount: "1" });
    const businessEdit = row("PROMO_AD_SUBMITTED", { promoterName: "Pat Promoter", adTitle: "Wash and fold", businessName: "Sparkle Laundry", adCount: "3" });
    const approved = row("PROMO_AD_APPROVED", { adTitle: "Wash and fold", businessName: "Sparkle Laundry" });
    const rejected = row("PROMO_AD_REJECTED", { adTitle: "Wash and fold", businessName: "Sparkle Laundry", reason: "Image is blurry" });

    it("words each in English like the server's own text", () => {
        expect(bare(notificationText(submitted, enT, "en").body)).toBe('Pat Promoter sent "Wash and fold" (Sparkle Laundry) for approval.');
        expect(notificationText(submitted, enT, "en").title).toBe("Ad awaiting approval");
        expect(bare(notificationText(approved, enT, "en").body)).toBe('Your ad "Wash and fold" (Sparkle Laundry) was approved and runs from its start date.');
        expect(notificationText(approved, enT, "en").title).toBe("Ad approved");
        expect(bare(notificationText(rejected, enT, "en").body)).toBe('Your ad "Wash and fold" (Sparkle Laundry) was rejected: Image is blurry');
        expect(notificationText(rejected, enT, "en").title).toBe("Ad rejected");
    });

    it("rebuilds each in Arabic, every value isolated", () => {
        const s = notificationText(submitted, arT, "ar");
        expect(s.title).toBe("إعلان بانتظار الموافقة");
        expect(s.body).toBe(`أرسل ${FSI}Pat Promoter${PDI} الإعلان «${FSI}Wash and fold${PDI}» (${FSI}Sparkle Laundry${PDI}) للموافقة.`);
        expect(bare(notificationText(approved, arT, "ar").body))
            .toBe("تمت الموافقة على إعلانك «Wash and fold» (Sparkle Laundry) وسيظهر اعتبارًا من تاريخ بدايته.");
        const r = notificationText(rejected, arT, "ar");
        expect(r.title).toBe("تم رفض الإعلان");
        expect(r.body).toBe(`تم رفض إعلانك «${FSI}Wash and fold${PDI}» (${FSI}Sparkle Laundry${PDI}): ${FSI}Image is blurry${PDI}`);
    });

    it("words a business edit that resubmits several ads as one notice, like the server", () => {
        const en = notificationText(businessEdit, enT, "en");
        expect(en.title).toBe("3 ads awaiting approval");
        expect(bare(en.body)).toBe('Pat Promoter changed Sparkle Laundry, so 3 of its ads (including "Wash and fold") wait for approval.');
        const a = notificationText(businessEdit, arT, "ar");
        expect(bare(a.title)).toBe("3 إعلانات بانتظار الموافقة");
        expect(bare(a.body)).toBe("عدّل Pat Promoter النشاط التجاري Sparkle Laundry، لذا تنتظر 3 من إعلاناته (منها «Wash and fold») الموافقة.");
    });

    it("gives the Arabic count its own form for every number, not just one and the rest", () => {
        const at = (n: number) => notificationText(
            row("PROMO_AD_SUBMITTED", { promoterName: "Pat Promoter", adTitle: "Wash and fold", businessName: "Sparkle Laundry", adCount: String(n) }),
            arT, "ar");
        const edit = (tail: string) => `عدّل Pat Promoter النشاط التجاري Sparkle Laundry، ${tail}`;

        // one: a single ad submitted, its own sentence.
        expect(bare(at(1).title)).toBe("إعلان بانتظار الموافقة");
        expect(bare(at(1).body)).toBe("أرسل Pat Promoter الإعلان «Wash and fold» (Sparkle Laundry) للموافقة.");
        // two: the dual.
        expect(bare(at(2).title)).toBe("إعلانان بانتظار الموافقة");
        expect(bare(at(2).body)).toBe(edit("لذا ينتظر إعلاناه (ومنهما «Wash and fold») الموافقة."));
        // few: 3 to 10, a plural noun.
        for (const n of [3, 10]) {
            expect(bare(at(n).title), String(n)).toBe(`${n} إعلانات بانتظار الموافقة`);
            expect(bare(at(n).body), String(n)).toBe(edit(`لذا تنتظر ${n} من إعلاناته (منها «Wash and fold») الموافقة.`));
        }
        // many: 11 to 99, a singular accusative noun.
        for (const n of [11, 25, 99]) {
            expect(bare(at(n).title), String(n)).toBe(`${n} إعلانًا بانتظار الموافقة`);
            expect(bare(at(n).body), String(n)).toBe(edit(`لذا ينتظر ${n} إعلانًا من إعلاناته (منها «Wash and fold») الموافقة.`));
        }
        // other: 100 and up (CLDR's "other" for Arabic), a singular nominative noun.
        for (const n of [100, 102]) {
            expect(bare(at(n).title), String(n)).toBe(`${n} إعلان بانتظار الموافقة`);
            expect(bare(at(n).body), String(n)).toBe(edit(`لذا ينتظر ${n} إعلان من إعلاناته (منها «Wash and fold») الموافقة.`));
        }
        // zero: never sent, but defined like every other Arabic plural in the catalog.
        expect(bare(at(0).title)).toBe("لا إعلانات بانتظار الموافقة");
    });

    it("defines every Arabic plural category in the title and the body, as the rest of the catalog does", () => {
        const m = ar.Notifications.messages.PROMO_AD_SUBMITTED;
        for (const text of [m.title, m.body]) {
            for (const category of ["zero", "one", "two", "few", "many", "other"]) {
                expect(text, category).toMatch(new RegExp(`\\b${category} \\{`));
            }
        }
    });

    it("keeps English to one and other, where English has no more", () => {
        const a = (n: number) => notificationText(
            row("PROMO_AD_SUBMITTED", { promoterName: "Pat Promoter", adTitle: "T", businessName: "B", adCount: String(n) }), enT, "en");
        expect(a(1).title).toBe("Ad awaiting approval");
        expect(a(2).title).toBe("2 ads awaiting approval");
        expect(a(11).title).toBe("11 ads awaiting approval");
    });

    it("shows a rejection reason as written, even when it looks like a code", () => {
        // 'OTHER' is a penalty-reason code elsewhere; here it is the admin's own word.
        const odd = row("PROMO_AD_REJECTED", { adTitle: "A", businessName: "B", reason: "OTHER" });
        expect(bare(notificationText(odd, enT, "en").body)).toBe('Your ad "A" (B) was rejected: OTHER');
        expect(notificationText(odd, arT, "ar").body).toContain(`${FSI}OTHER${PDI}`);
    });

    it("falls back to the stored English when a value is missing", () => {
        const noReason = row("PROMO_AD_REJECTED", { adTitle: "A", businessName: "B" }, "Ad rejected", "stored english");
        expect(notificationText(noReason, arT, "ar")).toEqual({ title: "Ad rejected", body: "stored english" });
    });

    it("labels the reference type in both languages", () => {
        expect(en.Notifications.referenceType.PROMO_AD).toBe("Promo ad");
        expect(ar.Notifications.referenceType.PROMO_AD).toBe("إعلان ترويجي");
    });
});

