import { describe, expect, it } from "vitest";
import { downloadFilename, filenameFromDisposition } from "../download";

describe("filenameFromDisposition", () => {
    it("prefers the UTF-8 filename* (an Arabic document name)", () => {
        const name = "هوية المستأجر.pdf";
        const header = `attachment; filename="_____ ________.pdf"; filename*=UTF-8''${encodeURIComponent(name)}`;
        expect(filenameFromDisposition(header)).toBe(name);
    });

    it("reads a quoted or bare filename", () => {
        expect(filenameFromDisposition('attachment; filename="aadhar.pdf"')).toBe("aadhar.pdf");
        expect(filenameFromDisposition("attachment; filename=contract-1.pdf")).toBe("contract-1.pdf");
    });

    it("is null without a name", () => {
        expect(filenameFromDisposition("attachment")).toBeNull();
        expect(filenameFromDisposition(null)).toBeNull();
    });
});

describe("downloadFilename", () => {
    it("uses the server's name, extension included", () => {
        const h = new Headers({ "content-disposition": "attachment; filename=\"aadhar.pdf\"; filename*=UTF-8''aadhar.pdf" });
        expect(downloadFilename(h, "aadhar")).toBe("aadhar.pdf");
    });

    it("adds the type's extension to a fallback that has none, once", () => {
        expect(downloadFilename(new Headers({ "content-type": "application/pdf" }), "aadhar")).toBe("aadhar.pdf");
        expect(downloadFilename(new Headers({ "content-type": "application/pdf" }), "aadhar.pdf")).toBe("aadhar.pdf");
        expect(downloadFilename(new Headers({ "content-type": "application/octet-stream" }), "aadhar")).toBe("aadhar");
    });
});
