import { describe, expect, it } from "vitest";
import { interestsCsv } from "../interestsCsv";
import type { InterestDTO } from "@/types/listing";

// Labels stand in for next-intl: the builder must take every header/status from
// the translator, never hard-code English.
const t = (key: string) => `[${key}]`;

function interest(over: Partial<InterestDTO>): InterestDTO {
    return {
        id: "i1", listingId: "l1", renterUserId: "r1",
        renterName: "Ahmed", renterEmail: "a@example.invalid", renterPhone: "+971500000000",
        note: null, status: "ACTIVE", createdAt: "2026-09-27T22:30:00Z",
        ...over,
    } as InterestDTO;
}

describe("interestsCsv (break-it R3 data3 F1)", () => {
    it("neutralises renter-typed formula cells: = + - @ tab CR", () => {
        const csv = interestsCsv(
            [
                interest({ renterName: '=HYPERLINK("http://evil.example/r","x")', note: "=cmd|' /C calc'!A0" }),
                interest({ renterName: "+1+1", note: "-2+3" }),
                interest({ renterName: "@SUM(A1)", note: "\t=1+1" }),
                interest({ renterName: "\r=1+1", note: "plain" }),
            ],
            t,
        );
        const lines = csv.split("\r\n");
        expect(lines[1]).toContain(`"'=HYPERLINK(""http://evil.example/r"",""x"")"`);
        expect(lines[1]).toContain(`"'=cmd|' /C calc'!A0"`);
        expect(lines[2]).toContain(`"'+1+1"`);
        expect(lines[2]).toContain(`"'-2+3"`);
        expect(lines[3]).toContain(`"'@SUM(A1)"`);
        expect(lines[3]).toContain(`"'\t=1+1"`);
        // No cell anywhere starts with a live trigger after its opening quote.
        for (const line of lines.slice(1)) {
            expect(line).not.toMatch(/(^|,)"[=+\-@\t]/);
        }
    });

    it("the phone's leading + is prefixed too (text, not a formula) and Arabic survives", () => {
        const csv = interestsCsv([interest({ renterName: "أحمد الهاشمي", note: "أبحث عن شقة" })], t);
        expect(csv).toContain('"أحمد الهاشمي"');
        expect(csv).toContain('"أبحث عن شقة"');
        expect(csv).toContain(`"'+971500000000"`);
    });

    it("uses translated headers and status labels, CRLF, and the Dubai calendar date", () => {
        const csv = interestsCsv([interest({ status: "CONVERTED" })], t);
        const [header, row] = csv.split("\r\n");
        expect(header).toBe('"[interestName]","[interestEmail]","[interestPhone]","[interestNote]","[interestDate]","[interestStatus]"');
        // 22:30Z on the 27th is 02:30 on the 28th in Dubai.
        expect(row).toContain('"2026-09-28"');
        expect(row).toContain('"[interestStatusConverted]"');
    });
});

describe("downloadCsv (the interests export's download path)", () => {
    it("writes a UTF-8 BOM before the content so Excel shows Arabic", async () => {
        const { downloadCsv } = await import("@/lib/csv");
        let captured: Blob | null = null;
        const origCreate = URL.createObjectURL;
        const origRevoke = URL.revokeObjectURL;
        URL.createObjectURL = ((b: Blob) => { captured = b; return "blob:x"; }) as typeof URL.createObjectURL;
        URL.revokeObjectURL = (() => {}) as typeof URL.revokeObjectURL;
        try {
            downloadCsv("interests.csv", interestsCsv([interest({ renterName: "أحمد" })], t));
        } finally {
            URL.createObjectURL = origCreate;
            URL.revokeObjectURL = origRevoke;
        }
        expect(captured).not.toBeNull();
        // jsdom's Blob has no arrayBuffer(); FileReader is the portable read.
        const buf = await new Promise<ArrayBuffer>((resolve, reject) => {
            const r = new FileReader();
            r.onload = () => resolve(r.result as ArrayBuffer);
            r.onerror = () => reject(r.error);
            r.readAsArrayBuffer(captured as unknown as Blob);
        });
        const bytes = new Uint8Array(buf);
        expect(Array.from(bytes.slice(0, 3))).toEqual([0xef, 0xbb, 0xbf]);
    });
});
