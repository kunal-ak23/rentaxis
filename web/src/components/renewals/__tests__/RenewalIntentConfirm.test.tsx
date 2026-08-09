import { cleanup, fireEvent, render, screen, act, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import RenewalIntentConfirm from "../RenewalIntentConfirm";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars) return `${key}:${JSON.stringify(vars)}`;
    return key;
  },
  useLocale: () => "en",
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("RenewalIntentConfirm", () => {
  it("fires fetch on Confirm click and navigates after 1200ms on success", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    // Stub window.location.href setter
    let capturedHref = "";
    Object.defineProperty(window, "location", {
      configurable: true,
      writable: true,
      value: {
        ...window.location,
        set href(v: string) { capturedHref = v; },
        get href() { return capturedHref || "http://localhost/"; },
      },
    });

    // Use a real Promise so fetch resolves properly even with fake timers
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ redirectTo: "/dashboard/renter-portal/renewals" }),
      })
    );
    global.fetch = fetchMock;

    render(<RenewalIntentConfirm token="tok-abc" intent="RENEW" />);

    const confirmBtn = screen.getByRole("button", { name: "confirm" });

    // Wrap click + microtask flush in act
    await act(async () => {
      fireEvent.click(confirmBtn);
      // Flush all pending microtasks/promises
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    // Fetch should have been called
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/proxy/v1/public/renewal-intent",
      expect.objectContaining({ method: "POST" })
    );

    // "done" state shows captured message
    expect(screen.getByText("captured")).toBeTruthy();

    // Advance fake timers by 1200ms to trigger navigation setTimeout.
    // The backend path is locale-less; the component must prefix the active
    // locale because all web routes live under /[locale].
    act(() => {
      vi.advanceTimersByTime(1200);
    });
    expect(capturedHref).toBe("/en/dashboard/renter-portal/renewals");
  });

  it("renders already-resolved message and hides button on 409 response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ error: "ALREADY_RESOLVED" }),
    });
    global.fetch = fetchMock;

    render(<RenewalIntentConfirm token="tok-resolved" intent="RENEW" />);

    const confirmBtn = screen.getByRole("button", { name: "confirm" });
    fireEvent.click(confirmBtn);

    await waitFor(() => screen.getByText("alreadyResolved"));

    // No generic error, and the confirm button is gone — nothing left to confirm.
    expect(screen.queryByText("genericError")).toBeNull();
    expect(screen.queryByRole("button", { name: "confirm" })).toBeNull();
  });

  it("renders expired message and hides button on 410 response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 410,
      json: async () => ({}),
    });
    global.fetch = fetchMock;

    render(<RenewalIntentConfirm token="tok-expired" intent="MOVE_OUT" />);

    const confirmBtn = screen.getByRole("button", { name: "confirm" });
    fireEvent.click(confirmBtn);

    await waitFor(() => screen.getByText("tokenExpired"));

    // Button should be hidden (expired state removes the button)
    expect(screen.queryByRole("button", { name: "confirm" })).toBeNull();
  });
});
