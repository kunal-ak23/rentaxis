import { describe, expect, it } from "vitest";
import { bookmarkletHref, clipboardPayload, FILL_SOURCE } from "../fillFromMiftah";

describe("fillFromMiftah", () => {
    it("clipboardPayload is the MIFTAH-DLD prefix plus the JSON", () => {
        const d = { end: "31/12/2026", typeLabel: "Apartment" as const, number: "123456789", rent: 85000 };
        expect(clipboardPayload(d)).toBe(
            'MIFTAH-DLD:{"end":"31/12/2026","typeLabel":"Apartment","number":"123456789","rent":85000}',
        );
    });

    it("bookmarkletHref is a javascript: URL that decodes to the fill source", () => {
        const href = bookmarkletHref();
        expect(href.startsWith("javascript:")).toBe(true);
        const src = decodeURIComponent(href.slice("javascript:".length));
        expect(src).toBe(FILL_SOURCE);
        expect(src).toContain("#dewaNumber");
        expect(src).toContain("#annualRent");
        expect(src).toContain("#ContractEndDate");
        expect(src).toContain("MIFTAH-DLD:");
    });
});
