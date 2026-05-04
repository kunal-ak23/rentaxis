import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import ChequeScanner from "../ChequeScanner";

vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) =>
    ({
      scanButton: "Scan Cheque",
      close: "Close",
      uploadPrompt: "Upload a cheque photo to auto-fill values.",
      choosePhoto: "Choose photo",
      extracting: "Reading cheque...",
      uploadingAndExtracting: "Uploading and extracting...",
      genericError: "Couldn't read this cheque. Please try again.",
      invalidType: "Use a JPG, PNG or HEIC image",
      fileTooLarge: "Photo too large; max 10MB",
      lowConfidenceBanner: "AI confidence is low. Please verify values.",
      extractionFailed: "Couldn't read this cheque automatically. Save the photo and fill manually?",
      attachPhoto: "Attach photo and continue",
      rescan: "Re-scan",
      useValues: "Use these values",
    })[key] ?? key,
}));

vi.mock("../useChequeExtraction", () => ({
  useChequeExtraction: () => ({
    isPending: false,
    error: null,
    data: null,
    mutateAsync: vi.fn(),
  }),
}));

afterEach(() => cleanup());

describe("ChequeScanner", () => {
  it("renders Scan Cheque button", () => {
    render(<ChequeScanner onExtracted={vi.fn()} />);
    expect(screen.getByRole("button", { name: /scan cheque/i })).toBeInTheDocument();
  });

  it("opens modal on click", () => {
    render(<ChequeScanner onExtracted={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /scan cheque/i }));
    expect(screen.getByText(/choose photo/i)).toBeInTheDocument();
  });
});
