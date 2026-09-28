import { describe, expect, it } from "vitest";
import { formatSlotInDubai } from "../slotText";

// Break-it R3 portal3 F4: the suggested slot is Dubai local time in the user's locale, never raw UTC.
describe("formatSlotInDubai", () => {
    it("shows a UTC instant as the Dubai wall-clock time (UTC+4)", () => {
        const en = formatSlotInDubai("2026-10-02T06:30:00Z", "en");
        expect(en).toContain("10:30");
        expect(en).toMatch(/am/i);
        expect(en).toContain("2026");
        expect(en).not.toContain("06:30");
        expect(en).not.toContain("T06");
        expect(en).not.toContain("Z");
    });

    it("formats in Arabic under /ar", () => {
        const ar = formatSlotInDubai("2026-10-02T06:30:00Z", "ar");
        expect(ar).not.toContain("T06:30");
        expect(ar).toMatch(/[؀-ۿ]/);
        expect(ar).toMatch(/10:30|١٠:٣٠/);
    });

    it("returns null for anything that is not an instant (e.g. the server's 'unavailable')", () => {
        expect(formatSlotInDubai("unavailable", "en")).toBeNull();
        expect(formatSlotInDubai(null, "en")).toBeNull();
        expect(formatSlotInDubai(undefined, "ar")).toBeNull();
    });
});
