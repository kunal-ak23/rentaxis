import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import LogInteractionDialog from "../LogInteractionDialog";

// Mock next-intl's useTranslations to return the key suffix directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string) => key,
}));

const defaultProps = {
  leaseId: "lease-123",
  onClose: vi.fn(),
  onSuccess: vi.fn(),
};

beforeEach(() => {
  vi.clearAllMocks();
  global.fetch = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("LogInteractionDialog", () => {
  it("renders with role=dialog and aria-modal=true", () => {
    render(<LogInteractionDialog {...defaultProps} />);
    const dialog = screen.getByRole("dialog");
    expect(dialog).toBeTruthy();
    expect(dialog.getAttribute("aria-modal")).toBe("true");
  });

  it("aria-labelledby points to the heading", () => {
    render(<LogInteractionDialog {...defaultProps} />);
    const dialog = screen.getByRole("dialog");
    const titleId = dialog.getAttribute("aria-labelledby");
    expect(titleId).toBeTruthy();
    const heading = document.getElementById(titleId!);
    expect(heading).toBeTruthy();
    expect(heading!.textContent).toContain("logInteraction");
  });

  it("submit with empty summary shows an error and does NOT call fetch", async () => {
    render(<LogInteractionDialog {...defaultProps} />);

    // Summary textarea starts empty — submit directly
    const form = screen.getByRole("dialog").querySelector("form")!;
    fireEvent.submit(form);

    expect(global.fetch).not.toHaveBeenCalled();
    // Error message should appear
    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
  });

  it("successful submit POSTs JSON to the right URL and calls onSuccess", async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      text: async () => "",
    });

    render(<LogInteractionDialog {...defaultProps} />);

    // Fill in summary
    const summaryEl = screen.getByRole("textbox");
    fireEvent.change(summaryEl, { target: { value: "Called renter about renewal" } });

    // Submit
    const form = screen.getByRole("dialog").querySelector("form")!;
    fireEvent.submit(form);

    await waitFor(() => expect(defaultProps.onSuccess).toHaveBeenCalledTimes(1));

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [url, opts] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/proxy/v1/leases/lease-123/interactions");
    expect(opts.method).toBe("POST");
    expect((opts.headers as Record<string, string>)["Content-Type"]).toBe("application/json");

    const body = JSON.parse(opts.body as string);
    expect(body.summary).toBe("Called renter about renewal");
    expect(body.type).toBe("CALL");
    expect(body.direction).toBe("OUTBOUND");
    expect(typeof body.occurredAt).toBe("string");
  });

  it("Escape key calls onClose", async () => {
    render(<LogInteractionDialog {...defaultProps} />);

    fireEvent.keyDown(window, { key: "Escape" });

    await waitFor(() => expect(defaultProps.onClose).toHaveBeenCalledTimes(1));
  });
});
