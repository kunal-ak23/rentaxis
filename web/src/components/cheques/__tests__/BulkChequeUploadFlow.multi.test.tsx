import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import BulkChequeUploadFlow from "../BulkChequeUploadFlow";
import type { Cheque } from "@/lib/api/leasing";

// One photo holding three cheques: /extract-many answers with three items, each
// with its own crop, and the file becomes three review rows.

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key,
}));

vi.mock("next/image", () => ({
  default: (props: React.ImgHTMLAttributes<HTMLImageElement>) => {
    // eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text
    return <img {...props} />;
  },
}));

function makeCheque(over: Partial<Cheque> & { id: string; seqNo: number; chequeDate: string; amount: number }): Cheque {
  return {
    leaseId: "L1", propertyId: "p1", unitId: "u1", renterId: "r1",
    propertyName: null, unitIdentifier: null, renterName: null,
    chequeNumber: null, payeeBank: null, payerName: null,
    debitAccountId: null, debitAccountName: null, postingDate: "2026-01-05",
    narration: null, mode: "PDC", status: "REGISTERED",
    failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
    depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
    pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
    due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
    ...over,
  };
}

const rows: Cheque[] = [
  makeCheque({ id: "r1", seqNo: 1, chequeDate: "2026-02-01", amount: 12750 }),
  makeCheque({ id: "r2", seqNo: 2, chequeDate: "2026-05-01", amount: 12750 }),
  makeCheque({ id: "r3", seqNo: 3, chequeDate: "2026-08-01", amount: 12750 }),
  makeCheque({ id: "r4", seqNo: 4, chequeDate: "2026-11-01", amount: 12750 }),
];

const detected = (n: number, date: string, flags: string[] = []) => ({
  imageId: `img-${n}`,
  image: { url: `https://blob/crop-${n}.jpg`, blobPath: `cheques/crop-${n}.jpg`, uploadedAt: "2026-09-29T00:00:00Z" },
  page: 1,
  box: { x: 0.1 * n, y: 0.1, width: 0.2, height: 0.15 },
  thumbnailUrl: `data:image/jpeg;base64,THUMB${n}`,
  extracted: { chequeNumber: `00030${n}`, bankName: "ENBD", payerName: "R", chequeDate: date, amount: 12750, confidence: "HIGH" },
  warnings: flags.length ? ["Could not separate this cheque cleanly — check the crop"] : [],
  flags,
});

const threeInOne = {
  original: { url: "https://blob/original.png", blobPath: "cheques/original.png", uploadedAt: "2026-09-29T00:00:00Z" },
  pages: [{ page: 1, previewUrl: "data:image/jpeg;base64,PAGE" }],
  // Read out of order: the auto-map, not the order, decides the row.
  items: [detected(3, "2026-08-02"), detected(1, "2026-02-01", ["crop_unreliable"]), detected(2, "2026-04-30")],
  warnings: [],
};

beforeEach(() => {
  global.URL.createObjectURL = vi.fn(() => "blob:mock");
  global.URL.revokeObjectURL = vi.fn();
  if (!("randomUUID" in (global.crypto ?? {}))) {
    // @ts-expect-error -- jsdom's crypto has no randomUUID; stub it for the test
    global.crypto = { ...global.crypto, randomUUID: () => `id-${Math.random().toString(36).slice(2)}` };
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function uploadOnePhoto(fetchMock: ReturnType<typeof vi.fn>, file = new File([new Uint8Array([1])], "three.jpg", { type: "image/jpeg" })) {
  global.fetch = fetchMock as unknown as typeof fetch;
  const onSuccess = vi.fn();
  render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={onSuccess} onClose={() => {}} />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  Object.defineProperty(input, "files", { value: [file], configurable: true });
  fireEvent.change(input);
  fireEvent.click(screen.getByText("continueToExtract"));
  await waitFor(() => screen.getByText("colChequeNumber"));
  return onSuccess;
}

describe("BulkChequeUploadFlow — several cheques in one file", () => {
  it("accepts PDFs as well as images", () => {
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.accept).toContain("application/pdf");
    expect(input.accept).toContain("image/*");
  });

  it("expands one file into one row per cheque, each with its own crop thumbnail", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => threeInOne });
    await uploadOnePhoto(fetchMock);

    expect(fetchMock.mock.calls[0][0]).toBe("/api/proxy/v1/cheques/extract-many");
    const thumbs = screen.getAllByTestId("cheque-crop-thumb").map(i => i.getAttribute("src"));
    expect(thumbs).toEqual(["data:image/jpeg;base64,THUMB3", "data:image/jpeg;base64,THUMB1", "data:image/jpeg;base64,THUMB2"]);
    expect(document.querySelectorAll("tbody tr")).toHaveLength(3);
  });

  it("shows the flag on a crop the server could not separate, and only there", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => threeInOne });
    await uploadOnePhoto(fetchMock);

    const flags = screen.getAllByText("cropUnreliable");
    expect(flags).toHaveLength(1);
    // It sits in the second row (cheque 000301).
    const row = flags[0].closest("tr")!;
    expect((row.querySelector("input") as HTMLInputElement).value).toBe("000301");
  });

  it("auto-maps every row by date and attaches three distinct images, one per cheque", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => threeInOne })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ cheques: [] }) });
    const onSuccess = await uploadOnePhoto(fetchMock);

    const selects = Array.from(document.querySelectorAll("select")).map(s => (s as HTMLSelectElement).value);
    expect(selects).toEqual(["r3", "r1", "r2"]);

    fireEvent.click(screen.getByText(/^approveAll/));
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());

    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe("/api/proxy/v1/leases/L1/cheques/bulk-attach");
    const items = JSON.parse(init.body).items as { chequeId: string; imageBlobPath: string; chequeNumber: string }[];
    expect(items).toHaveLength(3);
    expect(new Set(items.map(i => i.imageBlobPath)).size).toBe(3);
    expect(items.map(i => i.imageBlobPath)).not.toContain("cheques/original.png");
    const byCheque = Object.fromEntries(items.map(i => [i.chequeId, i.imageBlobPath]));
    expect(byCheque).toEqual({ r1: "cheques/crop-1.jpg", r2: "cheques/crop-2.jpg", r3: "cheques/crop-3.jpg" });
  });

  it("opens a crop next to its original page, with the cheque's box marked", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => threeInOne });
    await uploadOnePhoto(fetchMock);

    fireEvent.click(screen.getAllByLabelText("viewCrop")[1]);

    const dialog = screen.getByRole("dialog", { name: "viewCrop" });
    const srcs = Array.from(dialog.querySelectorAll("img")).map(i => i.getAttribute("src"));
    expect(srcs).toEqual(["data:image/jpeg;base64,THUMB1", "data:image/jpeg;base64,PAGE"]);
    expect(dialog.textContent).toContain("cropUnreliable");
    const box = dialog.querySelector(".border-primary") as HTMLElement;
    expect(box.style.left).toBe("10%");

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "viewCrop" })).toBeNull();
    // The flow itself is still open.
    expect(screen.getByText("colChequeNumber")).toBeTruthy();
  });

  it("removing one cheque's row keeps the other cheques of the same file", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => threeInOne });
    await uploadOnePhoto(fetchMock);

    fireEvent.click(screen.getAllByLabelText("removeRow")[0]);

    expect(document.querySelectorAll("tbody tr")).toHaveLength(2);
  });

  it("translates a coded refusal from the server", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: false, status: 400,
      json: async () => ({ error: "The PDF has 12 pages", code: "cheque_upload_too_many_pages" }),
    });
    await uploadOnePhoto(fetchMock, new File([new Uint8Array([1])], "scan.pdf", { type: "application/pdf" }));

    expect(screen.getByText(/uploadErrors\.cheque_upload_too_many_pages/)).toBeTruthy();
  });
});
