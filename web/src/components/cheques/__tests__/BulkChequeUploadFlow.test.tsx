import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import BulkChequeUploadFlow from "../BulkChequeUploadFlow";
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
    due: false, overdue: false, daysOverdue: 0,
    ...over,
  };
}

// Two REGISTERED, PDC rows the flow may attach scans to. These have no
// chequeDate, so matching falls back to postingDate.
const rows: Cheque[] = [
  makeCheque({ id: "s1", seqNo: 1, postingDate: "2026-06-05", amount: 5000 }),
  makeCheque({ id: "s2", seqNo: 2, postingDate: "2026-07-05", amount: 5000 }),
];

// A DEPOSITED row must never be offered — bulk-attach only edits REGISTERED rows.
const rowsWithDeposited: Cheque[] = [
  makeCheque({ id: "sd1", seqNo: 0, postingDate: "2026-06-01", amount: 10000, status: "DEPOSITED" }),
  makeCheque({ id: "s1", seqNo: 1, postingDate: "2026-06-05", amount: 5000 }),
  makeCheque({ id: "s2", seqNo: 2, postingDate: "2026-07-05", amount: 5000 }),
];

function makeFile(name: string): File {
  return new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: "image/png" });
}

beforeEach(() => {
  // jsdom doesn't ship URL.createObjectURL.
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

describe("BulkChequeUploadFlow", () => {
  it("auto-maps closest cheque date to a row and approves with chequeId in the payload", async () => {
    const fetchMock = vi.fn()
      // Two /extract calls:
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
          extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: "2026-06-04", confidence: "HIGH" },
          warnings: [],
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u2", blobPath: "b2", uploadedAt: "2026-05-07T00:00:01Z" },
          extracted: { chequeNumber: "C-2", bankName: "ENBD", payerName: "R", chequeDate: "2026-07-04", confidence: "HIGH" },
          warnings: [],
        }),
      })
      // bulk-attach call:
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ cheques: [] }),
      });
    global.fetch = fetchMock;

    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={onSuccess} onClose={() => {}} />);

    // Simulate folder pick.
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c1.png"), makeFile("c2.png")], configurable: true });
    fireEvent.change(input);

    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText("colChequeNumber"));

    // Both rows should auto-map: C-1 → s1, C-2 → s2.
    const selects = document.querySelectorAll("select");
    expect((selects[0] as HTMLSelectElement).value).toBe("s1");
    expect((selects[1] as HTMLSelectElement).value).toBe("s2");

    // Click approve.
    fireEvent.click(screen.getByText(/^approveAll/));

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());

    // bulk-attach was the 3rd call, and targets the cheque row by chequeId.
    const lastCall = fetchMock.mock.calls[2];
    expect(lastCall[0]).toBe("/api/proxy/v1/leases/L1/cheques/bulk-attach");
    const body = JSON.parse(lastCall[1].body);
    expect(body.items.map((i: { chequeId: string }) => i.chequeId).sort()).toEqual(["s1", "s2"]);
  });

  it("matches scans by each row's chequeDate, not the shared v2 postingDate (#62)", async () => {
    // Accounting v2: every wizard row carries the same contract postingDate;
    // only chequeDate distinguishes them.
    const v2Rows: Cheque[] = [
      makeCheque({ id: "r1", seqNo: 1, postingDate: "2026-04-20", chequeDate: "2026-05-01", amount: 5000 }),
      makeCheque({ id: "r2", seqNo: 2, postingDate: "2026-04-20", chequeDate: "2026-08-01", amount: 5000 }),
      makeCheque({ id: "r3", seqNo: 3, postingDate: "2026-04-20", chequeDate: "2026-11-01", amount: 5000 }),
      makeCheque({ id: "r4", seqNo: 4, postingDate: "2026-04-20", chequeDate: "2027-02-01", amount: 5000 }),
    ];
    const scan = (n: string, date: string) => ({
      ok: true,
      json: async () => ({
        image: { url: `u${n}`, blobPath: `b${n}`, uploadedAt: "2026-05-07T00:00:00Z" },
        extracted: { chequeNumber: `C-${n}`, bankName: "ENBD", payerName: "R", chequeDate: date, amount: 5000, confidence: "HIGH" },
        warnings: [],
      }),
    });
    // Out of order on purpose: the first scan is the last cheque.
    global.fetch = vi.fn()
      .mockResolvedValueOnce(scan("4", "2027-02-01"))
      .mockResolvedValueOnce(scan("2", "2026-08-01"));

    render(<BulkChequeUploadFlow leaseId="L1" rows={v2Rows} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("d.png"), makeFile("b.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));
    await waitFor(() => screen.getByText("colChequeNumber"));

    const selects = Array.from(document.querySelectorAll("select")) as HTMLSelectElement[];
    const byNumber = new Map(
      Array.from(document.querySelectorAll("tbody tr")).map((tr, i) => [
        (tr.querySelector("input") as HTMLInputElement).value,
        selects[i].value,
      ]),
    );
    expect(byNumber.get("C-4")).toBe("r4");
    expect(byNumber.get("C-2")).toBe("r2");

    // The picker labels rows by their cheque date, not the shared posting date.
    const labels = Array.from(selects[0].options).map(o => o.textContent ?? "");
    expect(labels.some(l => l.includes("20/04/2026") || l.includes("2026-04-20"))).toBe(false);
  });

  it("a failed extraction can be retried and fills the row, keeping edits typed while it runs (#64)", async () => {
    let resolveRetry: (v: unknown) => void = () => {};
    global.fetch = vi.fn()
      // First attempt fails outright.
      .mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({ error: "upstream" }) })
      // Retry is held open so the operator can type while it is in flight.
      .mockImplementationOnce(() => new Promise(r => { resolveRetry = r; }));

    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText("extractionFailed"));
    const [chequeNo, bank, payer] = Array.from(document.querySelectorAll("tbody input")) as HTMLInputElement[];
    expect(chequeNo.value).toBe("");
    expect((document.querySelector("select") as HTMLSelectElement).value).toBe("");

    fireEvent.click(screen.getByText("retryExtraction"));
    await waitFor(() => screen.getByText("retryingExtraction"));

    // The operator types a bank while the retry is in flight (review m-5):
    // the merge must read the rows as they are when the response lands, not
    // a snapshot taken at click time.
    fireEvent.change(bank, { target: { value: "Mashreq" } });

    await act(async () => {
      resolveRetry({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
          extracted: { chequeNumber: "C-9", bankName: "ENBD", payerName: "GULF BREW CAFE LLC", chequeDate: "2026-07-04", amount: 5000, confidence: "HIGH" },
          warnings: [],
        }),
      });
    });

    await waitFor(() => expect(chequeNo.value).toBe("C-9"));
    expect(payer.value).toBe("GULF BREW CAFE LLC");
    expect(bank.value).toBe("Mashreq");
    expect((document.querySelector('tbody input[type="date"]') as HTMLInputElement).value).toBe("2026-07-04");
    // The newly-read date auto-maps the row, and the failure notice is gone.
    expect((document.querySelector("select") as HTMLSelectElement).value).toBe("s2");
    expect(screen.queryByText("extractionFailed")).toBeNull();
    expect(screen.queryByText("retryExtraction")).toBeNull();
    // With the image now uploaded, approve is enabled.
    expect(screen.getByText(/^approveAll/).closest("button")).not.toBeDisabled();
  });

  it("a double-clicked Retry extracts once (review m-3)", async () => {
    let resolveRetry: (v: unknown) => void = () => {};
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({ error: "upstream" }) })
      .mockImplementationOnce(() => new Promise(r => { resolveRetry = r; }))
      .mockImplementation(() => new Promise(() => {}));
    global.fetch = fetchMock;

    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));
    await waitFor(() => screen.getByText("extractionFailed"));

    // Both clicks land before React re-renders the button as disabled.
    const retry = screen.getByText("retryExtraction").closest("button") as HTMLButtonElement;
    act(() => {
      retry.click();
      retry.click();
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    await act(async () => {
      resolveRetry({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
          extracted: { chequeNumber: "C-9", bankName: "ENBD", payerName: "P", chequeDate: "2026-07-04", amount: 5000, confidence: "HIGH" },
          warnings: [],
        }),
      });
    });
    await waitFor(() => expect(screen.queryByText("extractionFailed")).toBeNull());
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("only offers REGISTERED PDC rows — a DEPOSITED row is not a bulk-attach target", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
        extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: "2026-06-01", confidence: "HIGH" },
        warnings: [],
      }),
    });
    global.fetch = fetchMock;

    render(<BulkChequeUploadFlow leaseId="L2" rows={rowsWithDeposited} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c1.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText("colChequeNumber"));

    const select = document.querySelector("select") as HTMLSelectElement;
    const options = Array.from(select.options).map(o => o.value);
    expect(options).not.toContain("sd1");
    expect(options).toContain("s1");
  });

  it("ready count = only fully-complete rows (overlapping buckets must not be double-subtracted)", async () => {
    const fetchMock = vi.fn()
      // row 1: extracts fully → auto-maps to s1
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
          extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: "2026-06-04", amount: 5000, confidence: "HIGH" },
          warnings: [],
        }),
      })
      // row 2: OCR failed → empty row (needs date AND bank AND row — overlapping buckets)
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u2", blobPath: "b2", uploadedAt: "2026-05-07T00:00:01Z" },
          extracted: null,
          warnings: ["Extraction failed"],
        }),
      });
    global.fetch = fetchMock;

    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("a.png"), makeFile("b.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText(/^approveAll/));
    // 1 complete + 1 empty row → ready must be 1, total 2 (old bug subtracted 3
    // overlapping buckets from the total and produced -1).
    const approve = screen.getByText(/^approveAll/);
    expect(approve.textContent).toContain('"ready":1');
    expect(approve.textContent).toContain('"total":2');
  });

  it("disables approve when a row is missing a target cheque row", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
        extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: null, confidence: "LOW" },
        warnings: [],
      }),
    });
    global.fetch = fetchMock;

    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("x.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText(/^approveAll/));
    const approve = screen.getByText(/^approveAll/) as HTMLButtonElement;
    expect(approve.closest("button")).toBeDisabled();
  });

  it("shows mismatch chip when cheque amount differs from the row's amount, approve stays enabled", async () => {
    const fetchMock = vi.fn()
      // extract call: cheque amount 4500, but row s1 is 5000
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
          extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: "2026-06-04", amount: 4500, confidence: "HIGH" },
          warnings: [],
        }),
      })
      // bulk-attach call:
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ cheques: [] }),
      });
    global.fetch = fetchMock;

    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={onSuccess} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c1.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText("colChequeNumber"));

    // Mismatch chip should appear (4500 ≠ 5000).
    // The mock renders the key + vars as JSON, so we match on the key name.
    expect(screen.getByText(/chequeMismatch/)).toBeInTheDocument();

    // Approve button should still be enabled (mismatch is non-blocking)
    const approve = screen.getByText(/^approveAll/) as HTMLButtonElement;
    expect(approve.closest("button")).not.toBeDisabled();
  });
});
