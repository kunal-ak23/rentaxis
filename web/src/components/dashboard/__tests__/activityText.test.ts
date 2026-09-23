import { describe, expect, it } from "vitest";
import { createTranslator } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import { activityText, isolate, type ActivityItem } from "../activityText";

/** The line as a reader sees it: the invisible isolates removed. */
const visible = (s: string) => s.replace(/[\u2068\u2069]/g, "");

/**
 * Gap #60: the feed printed "Payment #2 bounced - … (AED 31500.00)": the
 * schedule position instead of the cheque number, an ungrouped amount and
 * English-only wording.
 */

const BOUNCED: ActivityItem = {
    type: "PAYMENT_BOUNCED",
    description: "Payment #2 bounced - Unit G-01, Miftah Residences (AED 31500.00)",
    timestamp: "2026-09-20T08:00:00Z",
    chequeStatus: "BOUNCED",
    chequeNumber: "700102",
    seqNo: 2,
    unitNumber: "G-01",
    propertyName: "Miftah Residences",
    amount: 31500,
};

function translators(messages: typeof en, locale: string) {
    const t = createTranslator({ locale, messages, namespace: "Dashboard" });
    const tc = createTranslator({ locale, messages, namespace: "Cheques" });
    return [
        (k: string, v?: Record<string, string | number>) => t(k as never, v as never),
        (k: string) => tc(k as never),
    ] as const;
}

describe("activityText", () => {
    it("names the cheque number and groups the amount", () => {
        const [t, tc] = translators(en, "en");
        const text = visible(activityText(BOUNCED, t, tc));
        expect(text).toContain("cheque 700102");
        expect(text).toContain("31,500.00");
        expect(text).toContain("Bounced");
        expect(text).not.toContain("#2");
    });

    it("falls back to the schedule position when the cheque has no number", () => {
        const [t, tc] = translators(en, "en");
        expect(visible(activityText({ ...BOUNCED, chequeNumber: null }, t, tc))).toContain("payment #2");
    });

    it("renders in Arabic from the same facts", () => {
        const [t, tc] = translators(ar as unknown as typeof en, "ar");
        const text = visible(activityText(BOUNCED, t, tc));
        expect(text).toContain(ar.Cheques.status.BOUNCED);
        expect(text).toContain("الشيك 700102");
        expect(text).not.toContain("bounced");
    });

    it("isolates each inserted value so the Arabic line keeps its order (M-4)", () => {
        const [t, tc] = translators(ar as unknown as typeof en, "ar");
        const text = activityText(BOUNCED, t, tc);
        expect(text).toContain(isolate("G-01"));
        expect(text).toContain(isolate("Miftah Residences"));
        expect(text).toMatch(/\u2068[^\u2068\u2069]*31,500\.00\u2069/);
        // Unit, comma and property are no longer adjacent bare LTR runs.
        expect(text).toContain(`${isolate("G-01")}، ${isolate("Miftah Residences")}`);
        // Every isolate is closed.
        expect(text.split("\u2068").length).toBe(text.split("\u2069").length);
    });

    it("uses the server's sentence when an older server sends no facts", () => {
        const [t, tc] = translators(en, "en");
        const legacy: ActivityItem = { type: "PAYMENT_BOUNCED", description: "legacy line", timestamp: BOUNCED.timestamp };
        expect(activityText(legacy, t, tc)).toBe("legacy line");
    });
});
