import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import BulkChequeUploadFlow from "../BulkChequeUploadFlow";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars) return `${key}:${JSON.stringify(vars)}`;
    return key;
  },
}));

vi.mock("next/image", () => ({
  default: (props: any) => {
    // eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text
    return <img {...props} />;
  },
}));

const schedules = [
  { id: "s1", installmentNumber: 1, dueDate: "2026-06-05", amount: 5000, status: "PENDING" },
  { id: "s2", installmentNumber: 2, dueDate: "2026-07-05", amount: 5000, status: "PENDING" },
];

// Schedules that include a charge/SD row at lease-start, same date as the first rent cheque.
const schedulesWithCharge = [
  { id: "sd1", installmentNumber: 0, dueDate: "2026-06-01", amount: 10000, status: "PENDING", isSecurityDeposit: true },
  { id: "s1", installmentNumber: 1, dueDate: "2026-06-05", amount: 5000, status: "PENDING" },
  { id: "s2", installmentNumber: 2, dueDate: "2026-07-05", amount: 5000, status: "PENDING" },
];

function makeFile(name: string): File {
  return new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: "image/png" });
}

beforeEach(() => {
  // jsdom doesn't ship URL.createObjectURL.
  global.URL.createObjectURL = vi.fn(() => "blob:mock");
  global.URL.revokeObjectURL = vi.fn();
  if (!("randomUUID" in (global.crypto ?? {}))) {
    // @ts-expect-error
    global.crypto = { ...global.crypto, randomUUID: () => `id-${Math.random().toString(36).slice(2)}` };
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("BulkChequeUploadFlow", () => {
  it("auto-maps closest cheque date to due date and approves successfully", async () => {
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
        json: async () => ({ schedules: [] }),
      });
    global.fetch = fetchMock;

    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" schedules={schedules} onSuccess={onSuccess} onClose={() => {}} />);

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

    // bulk-attach was the 3rd call.
    const lastCall = fetchMock.mock.calls[2];
    expect(lastCall[0]).toBe("/api/proxy/v1/leases/L1/cheques/bulk-attach");
  });

  it("disables approve when a row is missing a schedule", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
        extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: null, confidence: "LOW" },
        warnings: [],
      }),
    });
    global.fetch = fetchMock;

    render(<BulkChequeUploadFlow leaseId="L1" schedules={schedules} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("x.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText(/^approveAll/));
    const approve = screen.getByText(/^approveAll/) as HTMLButtonElement;
    expect(approve.closest("button")).toBeDisabled();
  });

  it("auto-map skips SD/charge rows — cheque dated at lease-start maps to rent installment, not security deposit", async () => {
    const fetchMock = vi.fn()
      // extract call: cheque dated 2026-06-01, same as the SD row's dueDate
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-06-01T00:00:00Z" },
          extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: "2026-06-01", confidence: "HIGH" },
          warnings: [],
        }),
      });
    global.fetch = fetchMock;

    render(
      <BulkChequeUploadFlow
        leaseId="L2"
        schedules={schedulesWithCharge}
        onSuccess={() => {}}
        onClose={() => {}}
      />
    );

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c1.png")], configurable: true });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText("colChequeNumber"));

    // The single cheque (dated 2026-06-01) should auto-map to s1 (2026-06-05),
    // NOT to sd1 (2026-06-01 — the security deposit row).
    const selects = document.querySelectorAll("select");
    expect((selects[0] as HTMLSelectElement).value).toBe("s1");

    // The SD row (sd1) must still be visible in the dropdown for manual selection.
    const options = Array.from((selects[0] as HTMLSelectElement).options).map(o => o.value);
    expect(options).toContain("sd1");
  });

  it("shows mismatch chip when cheque amount differs from installment amount, approve stays enabled", async () => {
    const fetchMock = vi.fn()
      // extract call: cheque amount 4500, but installment s1 is 5000
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
        json: async () => ({ schedules: [] }),
      });
    global.fetch = fetchMock;

    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" schedules={schedules} onSuccess={onSuccess} onClose={() => {}} />);
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
