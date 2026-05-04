import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import ChequeScanner from "../ChequeScanner";
import type { ChequeExtractionResponse } from "@/types/cheque";

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) =>
    ({
      scanButton: "Scan Cheque",
      title: "Log new cheque",
      breadcrumb: "Cheques · capture",
      close: "Close",
      step1: "Upload",
      step2: "Review",
      step3: "Confirm",
      uploadPrompt: "Upload a cheque photo to auto-fill values.",
      dropPrompt: "Drop cheque image or browse files",
      choosePhoto: "Choose photo",
      uploading: "Uploading…",
      extracting: "Reading cheque…",
      uploadingAndExtracting: "Uploading and extracting…",
      whatWeExtract: "What we'll extract",
      fieldChequeNumber: "Cheque number",
      fieldBankName: "Bank name",
      fieldPayerName: "Payer name",
      fieldChequeDate: "Cheque date",
      back: "Back",
      next: "Next",
      useValues: "Use these values",
      applyButton: "Apply to form",
      rescan: "Re-scan",
      genericError: "Couldn't read this cheque. Please try again.",
      extractionFailed: "Couldn't read this cheque automatically. Save the photo and fill manually?",
      attachPhoto: "Attach photo and continue",
      lowConfidenceBanner: "AI couldn't read this clearly — please double-check the values.",
      extractedBadge: "✨ Extracted",
      confidence: "Confidence",
      confidenceHigh: "High",
      confidenceMedium: "Medium",
      confidenceLow: "Low",
      fileTooLarge: "Photo too large; max 10MB",
      invalidType: "Use a JPG, PNG or HEIC image",
      notExtracted: "Not detected",
      imagePreview: "Cheque image",
    })[key] ?? key,
}));

vi.mock("next/image", () => ({
  default: (props: { src: string; alt: string }) => <img src={props.src} alt={props.alt} />,
}));

let mockState: {
  isPending: boolean;
  error: Error | null;
  data: ChequeExtractionResponse | null;
  mutateAsync: ReturnType<typeof vi.fn>;
  reset: ReturnType<typeof vi.fn>;
};

vi.mock("../useChequeExtraction", () => ({
  useChequeExtraction: () => mockState,
}));

beforeEach(() => {
  mockState = {
    isPending: false,
    error: null,
    data: null,
    mutateAsync: vi.fn(),
    reset: vi.fn(),
  };
  // jsdom doesn't implement createObjectURL
  Object.defineProperty(URL, "createObjectURL", { value: vi.fn(() => "blob:fake"), configurable: true });
});

afterEach(() => cleanup());

const happyResponse: ChequeExtractionResponse = {
  image: { url: "https://x/img.jpg", blobPath: "cheques/abc.jpg", uploadedAt: "2026-05-04T10:00:00Z" },
  extracted: {
    chequeNumber: "44182",
    bankName: "Emirates NBD",
    payerName: "RentAxis Property Mgmt LLC",
    chequeDate: "2026-06-01",
    confidence: "HIGH",
  },
  warnings: [],
};

const lowConfResponse: ChequeExtractionResponse = {
  ...happyResponse,
  extracted: { ...happyResponse.extracted!, confidence: "LOW" },
};

const failResponse: ChequeExtractionResponse = {
  image: happyResponse.image,
  extracted: null,
  warnings: ["bank name obscured"],
};

function pickFile() {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  const file = new File(["x"], "cheque.jpg", { type: "image/jpeg" });
  Object.defineProperty(input, "files", { value: [file], configurable: true });
  fireEvent.change(input);
  return file;
}

describe("ChequeScanner", () => {
  it("renders the scan button", () => {
    render(<ChequeScanner onExtracted={vi.fn()} />);
    expect(screen.getByRole("button", { name: /scan cheque/i })).toBeInTheDocument();
  });

  it("opens the modal with What we'll extract panel on step 1", () => {
    render(<ChequeScanner onExtracted={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    expect(screen.getByText(/what we'll extract/i)).toBeInTheDocument();
    expect(screen.getByText(/log new cheque/i)).toBeInTheDocument();
  });

  it("calls onExtracted with extracted fields after a successful scan + apply", async () => {
    mockState.mutateAsync = vi.fn(async () => {
      mockState.data = happyResponse;
      return happyResponse;
    });
    const onExtracted = vi.fn();
    render(<ChequeScanner onExtracted={onExtracted} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    pickFile();
    await waitFor(() => expect(screen.getByText("Emirates NBD")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /apply to form/i }));
    expect(onExtracted).toHaveBeenCalledWith(
      expect.objectContaining({
        chequeNumber: "44182",
        bankName: "Emirates NBD",
        payerName: "RentAxis Property Mgmt LLC",
        chequeDate: "2026-06-01",
        confidence: "HIGH",
        imageUrl: "https://x/img.jpg",
        imageBlobPath: "cheques/abc.jpg",
      })
    );
  });

  it("shows fallback when extraction returns null and applies image-only on click", async () => {
    mockState.mutateAsync = vi.fn(async () => {
      mockState.data = failResponse;
      return failResponse;
    });
    const onExtracted = vi.fn();
    render(<ChequeScanner onExtracted={onExtracted} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    pickFile();
    await waitFor(() => expect(screen.getByText(/couldn't read this cheque automatically/i)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /attach photo and continue/i }));
    expect(onExtracted).toHaveBeenCalledWith({
      imageUrl: "https://x/img.jpg",
      imageBlobPath: "cheques/abc.jpg",
      imageUploadedAt: "2026-05-04T10:00:00Z",
    });
  });

  it("shows the LOW-confidence banner when confidence is LOW", async () => {
    mockState.mutateAsync = vi.fn(async () => {
      mockState.data = lowConfResponse;
      return lowConfResponse;
    });
    render(<ChequeScanner onExtracted={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    pickFile();
    await waitFor(() =>
      expect(screen.getByText(/please double-check the values/i)).toBeInTheDocument()
    );
  });

  it("rejects too-large files without calling the network", () => {
    render(<ChequeScanner onExtracted={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const big = new File([new ArrayBuffer(11 * 1024 * 1024)], "big.jpg", { type: "image/jpeg" });
    Object.defineProperty(input, "files", { value: [big], configurable: true });
    fireEvent.change(input);
    expect(screen.getByText(/photo too large/i)).toBeInTheDocument();
    expect(mockState.mutateAsync).not.toHaveBeenCalled();
  });

  it("rejects invalid MIME types", () => {
    render(<ChequeScanner onExtracted={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const pdf = new File(["x"], "cheque.pdf", { type: "application/pdf" });
    Object.defineProperty(input, "files", { value: [pdf], configurable: true });
    fireEvent.change(input);
    expect(screen.getByText(/use a jpg, png or heic image/i)).toBeInTheDocument();
    expect(mockState.mutateAsync).not.toHaveBeenCalled();
  });
});
