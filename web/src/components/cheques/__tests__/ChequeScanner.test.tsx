import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import ChequeScanner from "../ChequeScanner";

vi.mock("../useChequeExtraction", () => ({
  useChequeExtraction: () => ({
    isPending: false,
    error: null,
    data: null,
    mutateAsync: vi.fn(),
  }),
}));

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
