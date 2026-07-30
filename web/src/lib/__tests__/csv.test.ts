import { describe, expect, it } from "vitest";
import { toCsv } from "../csv";

describe("toCsv", () => {
    it("quotes and escapes free text containing commas, quotes and newlines", () => {
        // The three characters that break a naive `row.join(",")`. Guest names and
        // purposes are free text, so all three reach this function in production.
        const csv = toCsv(
            ["Guest", "Purpose"],
            [
                ["Khan, Ahmed", "Delivery"],
                ['Ahmed "Abu" Khan', "Guest said \"back in 5\""],
                ["Line\nBreak", "Multi\r\nline"],
            ],
        );

        expect(csv).toBe(
            '"Guest","Purpose"\r\n' +
            // A comma inside a quoted field is data, not a delimiter.
            '"Khan, Ahmed","Delivery"\r\n' +
            // RFC 4180: a literal " is doubled.
            '"Ahmed ""Abu"" Khan","Guest said ""back in 5"""\r\n' +
            // Newlines survive inside the quotes rather than starting a new record.
            '"Line\nBreak","Multi\r\nline"',
        );
    });

    it("round-trips a comma'd and quoted field back to the original value", () => {
        // Asserting the bytes above is not the same as asserting the value survives.
        // This parses the output the way a spreadsheet does and compares to input.
        const original = 'Khan, "Abu" Ahmed';
        const body = toCsv(["Guest"], [[original]]).split("\r\n")[1];

        expect(body).toBe('"Khan, ""Abu"" Ahmed"');
        expect(body.slice(1, -1).replace(/""/g, '"')).toBe(original);
    });

    it("renders null and undefined as empty fields, not the strings 'null'/'undefined'", () => {
        // Every nullable column on the report row (vehicleNumber, purpose,
        // rejectionReason) hits this path on most rows.
        expect(toCsv(["A", "B", "C"], [[null, undefined, ""]])).toBe(
            '"A","B","C"\r\n"","",""',
        );
    });

    it("neutralises leading formula triggers in attacker-supplied text", () => {
        // Guest name is typed by a renter and read by a manager in Excel. Quoting
        // alone leaves the formula intact once the parser strips the quotes.
        const csv = toCsv(
            ["Guest"],
            [["=cmd|'/c calc'!A1"], ["+1 guest"], ["-5"], ["@SUM(A1)"]],
        );

        expect(csv.split("\r\n").slice(1)).toEqual([
            `"'=cmd|'/c calc'!A1"`,
            `"'+1 guest"`,
            `"'-5"`,
            `"'@SUM(A1)"`,
        ]);
    });

    it("leaves an interior =, + or - alone", () => {
        // Only a leading trigger is a formula; mangling interior characters would
        // corrupt ordinary values like a vehicle plate or a phone number.
        expect(toCsv(["V"], [["A-12345"], ["+971 50 123 4567"]]).split("\r\n").slice(1)).toEqual([
            '"A-12345"',
            `"'+971 50 123 4567"`,
        ]);
    });

    it("preserves Arabic text unchanged", () => {
        expect(toCsv(["الاسم"], [["أحمد خان"]])).toBe('"الاسم"\r\n"أحمد خان"');
    });

    it("emits a header-only document when there are no rows", () => {
        expect(toCsv(["A", "B"], [])).toBe('"A","B"');
    });
});
