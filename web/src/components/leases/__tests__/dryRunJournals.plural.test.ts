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
    it("AR picks the plural form for each category", () => {
        const ar1 = (tco: number, tcoLines: number, pdr: number) => fmt(ar.Leasing.dryRunJournals, "ar", { tco, tcoLines, pdr });
        expect(ar1(1, 1, 0)).toBe("قيد عقد واحد ببند واحد، ولا قيود شيكات آجلة");
        expect(ar1(2, 2, 1)).toBe("قيدا عقد ببندين، وقيد شيكات آجلة واحد");
        expect(ar1(1, 3, 2)).toMatch(/^قيد عقد واحد بـ \S+ بنود، وقيدا شيكات آجلة$/);
        expect(ar1(1, 11, 12)).toMatch(/^قيد عقد واحد بـ \S+ بندًا، و\S+ قيدًا للشيكات الآجلة$/);
        expect(ar1(1, 100, 100)).toMatch(/^قيد عقد واحد بـ \S+ بند، و\S+ قيد شيكات آجلة$/);
    });
});
