import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import BulkChequeUploadFlow, { isScannableRow } from "../BulkChequeUploadFlow";
import type { Cheque } from "@/lib/api/leasing";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars) return `${key}:${JSON.stringify(vars)}`;
    return key;
  },
}));

vi.mock("next/image", () => ({
  default: (props: React.ImgHTMLAttributes<HTMLImageElement>) => {
    // eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text
    return <img {...props} />;
  },
}));

function makeCheque(over: Partial<Cheque> & { id: string; seqNo: number; postingDate: string; amount: number }): Cheque {
  return {
    leaseId: "L1", propertyId: "p1", unitId: "u1", renterId: "r1",
    propertyName: null, unitIdentifier: null, renterName: null,
    chequeNumber: null, chequeDate: null, payeeBank: null, payerName: null,
    debitAccountId: null, debitAccountName: null,
    narration: null, mode: "PDC", status: "REGISTERED",
    failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
    depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
    pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
    due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
    ...over,
  };
}


function makeFile(name: string): File {
  return new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: "image/png" });
}

const scan = (n: string, date: string) => ({
  ok: true,
  json: async () => ({
    image: { url: `u${n}`, blobPath: `b${n}`, uploadedAt: "2026-05-07T00:00:00Z" },
    extracted: { chequeNumber: `C-${n}`, bankName: "ENBD", payerName: "R", chequeDate: date, amount: 5000, confidence: "HIGH" },
    warnings: [],
  }),
});

function pickPhotos(files: File[]) {
  const input = screen.getByTestId("bulk-cheque-upload-photos-input") as HTMLInputElement;
  Object.defineProperty(input, "files", { value: files, configurable: true });
  fireEvent.change(input);
}

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

describe("BulkChequeUploadFlow — restored entry points", () => {
  it("attaches to a draft contract's saved PDC rows (the wizard / draft grid scan)", async () => {
    const draftRows: Cheque[] = [
      makeCheque({ id: "d1", seqNo: 1, postingDate: "2026-04-20", chequeDate: "2026-05-01", amount: 5000, status: "DRAFT" }),
      makeCheque({ id: "d2", seqNo: 2, postingDate: "2026-04-20", chequeDate: "2026-08-01", amount: 5000, status: "DRAFT" }),
      // A cash row on the draft has no paper to scan.
      makeCheque({ id: "d3", seqNo: 3, postingDate: "2026-04-20", chequeDate: "2026-04-20", amount: 1000, status: "DRAFT", mode: "CASH" }),
    ];
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(scan("2", "2026-08-01"))
      .mockResolvedValueOnce({ ok: true, json: async () => ({ cheques: [] }) });
    global.fetch = fetchMock;
    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" rows={draftRows} onSuccess={onSuccess} onClose={() => {}} />);

    pickPhotos([makeFile("one.png")]);
    fireEvent.click(screen.getByText("continueToExtract"));
    await waitFor(() => screen.getByText("colChequeNumber"));

    const select = document.querySelector("select") as HTMLSelectElement;
    expect(select.value).toBe("d2");
    // Only the two PDC rows are targets.
    expect(Array.from(select.options).map(o => o.value).filter(Boolean)).toEqual(["d1", "d2"]);

    fireEvent.click(screen.getByText(/^approveAll/));
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    const body = JSON.parse(fetchMock.mock.calls[1][1].body);
    expect(body.items).toEqual([expect.objectContaining({ chequeId: "d2", chequeNumber: "C-2", imageBlobPath: "b2" })]);
  });

  it("a row's own Attach scan offers only that cheque as the target", async () => {
    const rows: Cheque[] = [
      makeCheque({ id: "r1", seqNo: 1, postingDate: "2026-04-20", chequeDate: "2026-05-01", amount: 5000 }),
      makeCheque({ id: "r2", seqNo: 2, postingDate: "2026-04-20", chequeDate: "2026-08-01", amount: 5000 }),
    ];
    global.fetch = vi.fn().mockResolvedValueOnce(scan("9", "2026-08-01"));
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onlyChequeId="r1" onSuccess={() => {}} onClose={() => {}} />);
    // One cheque: no folder picker, the one-photo hint.
    expect(screen.queryByTestId("bulk-cheque-upload-pick-folder")).toBeNull();
    expect(screen.getByText("pickHintOne")).toBeTruthy();
    // One cheque, one photo (PR #396 review P3-5).
    expect(screen.getByTestId("bulk-cheque-upload-photos-input").hasAttribute("multiple")).toBe(false);

    pickPhotos([makeFile("one.png")]);
    fireEvent.click(screen.getByText("continueToExtract"));
    await waitFor(() => screen.getByText("colChequeNumber"));
    const select = document.querySelector("select") as HTMLSelectElement;
    expect(Array.from(select.options).map(o => o.value).filter(Boolean)).toEqual(["r1"]);
    expect(select.value).toBe("r1");
  });

  it("says so when the contract has no cheque waiting for a scan", () => {
    const rows: Cheque[] = [makeCheque({ id: "x", seqNo: 1, postingDate: "2026-04-20", amount: 5000, status: "DEPOSITED" })];
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    expect(screen.getByTestId("bulk-cheque-upload-no-rows")).toBeTruthy();
    expect(screen.queryByTestId("bulk-cheque-upload-pick-photos")).toBeNull();
  });

  it("picks single photos without a folder (phones have no folder picker)", () => {
    const rows: Cheque[] = [makeCheque({ id: "r1", seqNo: 1, postingDate: "2026-04-20", amount: 5000 })];
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    const photos = screen.getByTestId("bulk-cheque-upload-photos-input");
    expect(photos.hasAttribute("webkitdirectory")).toBe(false);
    expect(photos.hasAttribute("multiple")).toBe(true);
    expect(screen.getByTestId("bulk-cheque-upload-pick-folder")).toBeTruthy();
  });
});

describe("isScannableRow", () => {
  it("is a PDC row the server still writes", () => {
    const c = (status: Cheque["status"], mode: Cheque["mode"] = "PDC") =>
      isScannableRow(makeCheque({ id: "a", seqNo: 1, postingDate: "2026-01-01", amount: 1, status, mode }));
    expect(c("DRAFT")).toBe(true);
    expect(c("REGISTERED")).toBe(true);
    expect(c("DEPOSITED")).toBe(false);
    expect(c("CLEARED")).toBe(false);
    expect(c("REGISTERED", "CASH")).toBe(false);
  });
});
