import { describe, expect, it } from "vitest";
import { IntlMessageFormat } from "intl-messageformat";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

// QA-20 review: the journals line is in words and pluralised in both languages.
describe("Leasing.dryRunJournals", () => {
    const fmt = (msg: string, locale: string, v: Record<string, number>) => String(new IntlMessageFormat(msg, locale).format(v));
    it("EN reads in words, singular and plural", () => {
        expect(fmt(en.Leasing.dryRunJournals, "en", { tco: 1, tcoLines: 1, pdr: 1 }))
            .toBe("One contract entry with one line, and one post-dated cheque entry");
        expect(fmt(en.Leasing.dryRunJournals, "en", { tco: 1, tcoLines: 4, pdr: 0 }))
            .toBe("One contract entry with 4 lines, and no post-dated cheque entries");
    });
    it("AR formats for each plural category without error", () => {
        for (const n of [0, 1, 2, 3, 11, 100]) {
            expect(fmt(ar.Leasing.dryRunJournals, "ar", { tco: 1, tcoLines: n, pdr: n })).not.toMatch(/[{}]/);
        }
    });
});
