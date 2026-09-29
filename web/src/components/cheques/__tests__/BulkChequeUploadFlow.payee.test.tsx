import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
    due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
    ...over,
  };
}

// Two REGISTERED, PDC rows the flow may attach scans to. These have no
// chequeDate, so matching falls back to postingDate.
const rows: Cheque[] = [
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

// Owner ruling 2026-09-29: with the organisation's payee check on, a scan whose
// payee matches none of the valid names is flagged in red with the name read,
// and is approved only after the operator ticks a confirmation. An unreadable
// payee gets a softer note and needs no confirmation.
function extractResponse(payeeCheck: string | null, payeeName: string | null) {
  return {
    ok: true,
    json: async () => ({
      image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
      extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", payeeName, chequeDate: "2026-06-04", confidence: "HIGH" },
      warnings: [],
      payeeCheck,
    }),
  };
}

async function scanOne(fetchMock: ReturnType<typeof vi.fn>, onSuccess = vi.fn()) {
  global.fetch = fetchMock as unknown as typeof fetch;
  render(<BulkChequeUploadFlow leaseId="L1" rows={[rows[0]]} onSuccess={onSuccess} onClose={() => {}} />);
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  Object.defineProperty(input, "files", { value: [makeFile("c1.png")], configurable: true });
  fireEvent.change(input);
  fireEvent.click(screen.getByText("continueToExtract"));
  await waitFor(() => screen.getByText("colChequeNumber"));
  return onSuccess;
}

const approveButton = () => screen.getByText(/^approveAll/).closest("button") as HTMLButtonElement;

describe("BulkChequeUploadFlow payee check", () => {
  it("flags a payee that matches none of the valid names and approves only after confirmation", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(extractResponse("MISMATCH", "Other Landlord LLC"))
      .mockResolvedValueOnce({ ok: true, json: async () => [] });
    const onSuccess = await scanOne(fetchMock);

    const flag = screen.getByTestId("payee-mismatch");
    expect(flag.textContent).toContain("payeeMismatch");
    expect(flag.textContent).toContain("Other Landlord LLC");
    expect(flag.className).toContain("red");
    expect(approveButton().disabled).toBe(true);

    fireEvent.click(screen.getByRole("checkbox", { name: /payeeMismatchConfirm/ }));
    expect(approveButton().disabled).toBe(false);
    fireEvent.click(approveButton());

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    const body = JSON.parse(fetchMock.mock.calls[1][1].body);
    expect(body.items[0].payeeMismatchConfirmed).toBe(true);
    expect(body.items[0]).not.toHaveProperty("payeeName");
  });

  it("notes an unreadable payee without asking for confirmation", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(extractResponse("UNREADABLE", null))
      .mockResolvedValueOnce({ ok: true, json: async () => [] });
    await scanOne(fetchMock);

    expect(screen.getByTestId("payee-unreadable").textContent).toContain("payeeUnreadable");
    expect(screen.queryByTestId("payee-mismatch")).toBeNull();
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(approveButton().disabled).toBe(false);
  });

  it("says nothing when the payee matches or the check is off", async () => {
    await scanOne(vi.fn().mockResolvedValueOnce(extractResponse("MATCH", "Palm Ridge Properties LLC")));
    expect(screen.queryByTestId("payee-mismatch")).toBeNull();
    expect(screen.queryByTestId("payee-unreadable")).toBeNull();
    cleanup();

    await scanOne(vi.fn().mockResolvedValueOnce(extractResponse(null, "Anyone")));
    expect(screen.queryByTestId("payee-mismatch")).toBeNull();
    expect(approveButton().disabled).toBe(false);
  });

  // Review m4: the server's refusal of an unconfirmed mismatch has its own message.
  it("explains a server refusal of an unconfirmed payee mismatch", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(extractResponse(null, "Other Landlord LLC"))
      .mockResolvedValueOnce({
        ok: false, status: 400,
        json: async () => ({ rows: [{ chequeId: "s1", reason: "payee_mismatch_unconfirmed" }] }),
      });
    await scanOne(fetchMock);
    fireEvent.click(approveButton());

    expect(await screen.findByText("payeeMismatchUnconfirmedError")).toBeTruthy();
    expect(screen.queryByText("rowConflictError")).toBeNull();
  });

  // PR #388 path: one photo holding two cheques; the check is per detected cheque.
  it("flags only the cheque in a multi-cheque photo whose payee does not match", async () => {
    const det = (n: string, date: string, payeeName: string, payeeCheck: string) => ({
      imageId: `img-${n}`, image: { url: `u-${n}`, blobPath: `b-${n}`, uploadedAt: "2026-05-07T00:00:00Z" },
      page: 1, box: null, thumbnailUrl: null, warnings: [], flags: [], payeeCheck,
      extracted: { chequeNumber: n, bankName: "ENBD", payerName: "R", payeeName, chequeDate: date, amount: 5000, confidence: "HIGH" },
    });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          original: { url: "o", blobPath: "o", uploadedAt: "2026-05-07T00:00:00Z" }, pages: [], warnings: [],
          items: [det("C-1", "2026-06-04", "Palm Ridge", "MATCH"), det("C-2", "2026-07-04", "Other Landlord LLC", "MISMATCH")],
        }),
      })
      .mockResolvedValueOnce({ ok: true, json: async () => [] });
    global.fetch = fetchMock as unknown as typeof fetch;
    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" rows={rows} onSuccess={onSuccess} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("two.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));
    await waitFor(() => screen.getByText("colChequeNumber"));

    const flags = screen.getAllByTestId("payee-mismatch");
    expect(flags).toHaveLength(1);
    expect(flags[0].textContent).toContain("Other Landlord LLC");
    expect(approveButton().disabled).toBe(true);

    fireEvent.click(screen.getByRole("checkbox", { name: /payeeMismatchConfirm/ }));
    fireEvent.click(approveButton());
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    const body = JSON.parse(fetchMock.mock.calls[1][1].body);
    const byPath = Object.fromEntries(body.items.map((i: { imageBlobPath: string }) => [i.imageBlobPath, i]));
    expect(byPath["b-C-2"].payeeMismatchConfirmed).toBe(true);
    expect(byPath["b-C-1"].payeeMismatchConfirmed).toBeUndefined();
  });
});
