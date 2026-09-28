import { describe, expect, it } from "vitest";
import { createTranslator } from "next-intl";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";
import { importErrorText, type ImportErrorsTranslator } from "../importErrorText";

const tEn = createTranslator({ locale: "en", messages: en, namespace: "ImportErrors" }) as unknown as ImportErrorsTranslator;
const tAr = createTranslator({ locale: "ar", messages: ar, namespace: "ImportErrors" }) as unknown as ImportErrorsTranslator;

describe("importErrorText (break-it R3 data3 F4)", () => {
    it("renders the two-tab race in the viewer's language", () => {
        const e = { message: "This file is already being imported — refresh to see the result", code: "import.alreadyRunning" };
        expect(importErrorText(tEn, e)).toBe("This file is already being imported — refresh to see the result");
        expect(importErrorText(tAr, e)).toMatch(/[؀-ۿ]/);
    });

    it("puts the reference in both languages", () => {
        const e = { message: "Import failed — reference 1A2B3C4D", code: "import.failedRef", args: { reference: "1A2B3C4D" } };
        expect(importErrorText(tEn, e)).toBe("Import failed — reference 1A2B3C4D");
        expect(importErrorText(tAr, e)).toContain("1A2B3C4D");
        const p = { message: "Posting failed — reference 9F9F9F9F", code: "post.failedRef", args: { reference: "9F9F9F9F" } };
        expect(importErrorText(tAr, p)).toContain("9F9F9F9F");
        expect(importErrorText(tEn, { message: "x", code: "post.alreadyRunning" })).toBe("This is already being posted — refresh to see the result");
    });

    it("leaves an uncoded or unknown-coded message as the server wrote it", () => {
        expect(importErrorText(tEn, { message: "Row 3: StartDate is required" })).toBe("Row 3: StartDate is required");
        expect(importErrorText(tEn, { message: "server words", code: "nope.never" })).toBe("server words");
    });
});
