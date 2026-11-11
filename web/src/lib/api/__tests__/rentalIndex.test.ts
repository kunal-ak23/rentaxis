import { describe, expect, it } from "vitest";
import { dewaPremiseNumberValid, dldRentalIndexUrl, parseRentalIndexCsv } from "../rentalIndex";

describe("parseRentalIndexCsv", () => {
    it("reads building, unit type, year, min, max (and a note) past a header row", () => {
        const rows = parseRentalIndexCsv([
            "building,unit type,year,min,max,note",
            "Sample Vista,2 BR,2026,83736,102344,DLD 11/11/2026",
            '"Sample Vista","1 BR",2027,"59,054","72,176"',
            "",
            ",Studio,2027,AED 40000,48000",
        ].join("\n"));
        expect(rows).toEqual([
            { row: 2, error: null, input: { building: "Sample Vista", unitType: "2 BR", indexYear: 2026, minValue: 83736, maxValue: 102344, sourceNote: "DLD 11/11/2026" } },
            { row: 3, error: null, input: { building: "Sample Vista", unitType: "1 BR", indexYear: 2027, minValue: 59054, maxValue: 72176, sourceNote: null } },
            { row: 5, error: null, input: { building: null, unitType: "Studio", indexYear: 2027, minValue: 40000, maxValue: 48000, sourceNote: null } },
        ]);
    });

    it("takes a first line with a four-digit year as data, not a header", () => {
        const rows = parseRentalIndexCsv("Tower,BHK2,2027,80522,98416\r\n");
        expect(rows).toHaveLength(1);
        expect(rows[0].input?.indexYear).toBe(2027);
    });

    it("names the rows it cannot read, by sheet row number", () => {
        const rows = parseRentalIndexCsv([
            "building,unit type,year,min,max",
            "Tower,BHK2,2027",
            "Tower,,2027,1,2",
            "Tower,BHK2,27,1,2",
            "Tower,BHK2,2027,abc,2",
        ].join("\n"));
        expect(rows.map(r => [r.row, r.error])).toEqual([
            [2, "expected 5 columns: building, unit type, year, min, max"],
            [3, "unit type is blank"],
            [4, 'year "27" is not a four-digit year'],
            [5, "min and max must be amounts"],
        ]);
        expect(rows.every(r => r.input === null)).toBe(true);
    });

    it("an empty sheet parses to nothing", () => {
        expect(parseRentalIndexCsv("building,unit type,year,min,max\n\n")).toEqual([]);
        expect(parseRentalIndexCsv("")).toEqual([]);
    });
});

describe("dewaPremiseNumberValid", () => {
    it("accepts nine digits or blank only", () => {
        expect(dewaPremiseNumberValid("123456789")).toBe(true);
        expect(dewaPremiseNumberValid(" 123456789 ")).toBe(true);
        expect(dewaPremiseNumberValid("")).toBe(true);
        expect(dewaPremiseNumberValid("12345678")).toBe(false);
        expect(dewaPremiseNumberValid("1234567890")).toBe(false);
        expect(dewaPremiseNumberValid("12345678a")).toBe(false);
    });
});

describe("dldRentalIndexUrl", () => {
    it("is the English page unless the locale is Arabic", () => {
        expect(dldRentalIndexUrl("en")).toBe("https://dubailand.gov.ae/en/eservices/rental-index/");
        expect(dldRentalIndexUrl("ar")).toBe("https://dubailand.gov.ae/ar/eservices/rental-index/");
    });
});
