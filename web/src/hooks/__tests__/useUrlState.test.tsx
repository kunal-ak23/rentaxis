import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

// Task 6: useUrlState is extracted from the Tickets list's propertyId/buildingId
// URL store (tickets/page.tsx) so Renters (and later Properties/Tickets) share
// one implementation. Contract: [value, setValue] backed by `location.search`,
// written with `history.replaceState` (never a navigation), reading the
// fallback when the key is absent, and removing the key from the URL when set
// to the fallback or to "".

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));

import { useUrlState } from "@/hooks/useUrlState";

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("useUrlState", () => {
    it("reads the fallback when the key is absent from the URL", () => {
        const { result } = renderHook(() => useUrlState("q", ""));
        expect(result.current[0]).toBe("");
    });

    it("reads an existing key's value on first render", () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?q=sara&page=2");
        const { result } = renderHook(() => useUrlState("q", ""));
        expect(result.current[0]).toBe("sara");
    });

    it("writes the key to the URL via history.replaceState, not pushState", () => {
        const replaceSpy = vi.spyOn(window.history, "replaceState");
        const pushSpy = vi.spyOn(window.history, "pushState");
        const { result } = renderHook(() => useUrlState("q", ""));

        act(() => result.current[1]("sara"));

        expect(replaceSpy).toHaveBeenCalled();
        expect(pushSpy).not.toHaveBeenCalled();
        expect(new URL(window.location.href).searchParams.get("q")).toBe("sara");
    });

    it("removes the key when set to empty string", () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?q=sara");
        const { result } = renderHook(() => useUrlState("q", ""));

        act(() => result.current[1](""));

        expect(new URL(window.location.href).searchParams.has("q")).toBe(false);
    });

    it("removes the key when set to the fallback value", () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?page=3");
        const { result } = renderHook(() => useUrlState("page", "1"));

        act(() => result.current[1]("1"));

        expect(new URL(window.location.href).searchParams.has("page")).toBe(false);
    });

    it("leaves every other query parameter untouched", () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?propertyId=p1&buildingId=b1");
        const { result } = renderHook(() => useUrlState("q", ""));

        act(() => result.current[1]("sara"));

        const params = new URL(window.location.href).searchParams;
        expect(params.get("propertyId")).toBe("p1");
        expect(params.get("buildingId")).toBe("b1");
        expect(params.get("q")).toBe("sara");
    });

    it("two independent keys on the same page don't clobber each other", () => {
        const q = renderHook(() => useUrlState("q", ""));
        const page = renderHook(() => useUrlState("page", "1"));

        act(() => q.result.current[1]("sara"));
        act(() => page.result.current[1]("3"));

        const params = new URL(window.location.href).searchParams;
        expect(params.get("q")).toBe("sara");
        expect(params.get("page")).toBe("3");
    });

    it("returns a referentially stable setter across re-renders for the same key/fallback", () => {
        // Fix round 1 (Task 7 review): consumers (Tickets/Renters/Properties'
        // fetch callbacks and debounce effects) list the setter in their own
        // dependency arrays. If it were a fresh closure every render, doing
        // so would refire those effects on every render instead of only when
        // a real value changes.
        const { result, rerender } = renderHook(() => useUrlState("q", ""));
        const first = result.current[1];
        rerender();
        expect(result.current[1]).toBe(first);

        act(() => result.current[1]("sara"));
        rerender();
        expect(result.current[1]).toBe(first);
    });

    it("re-renders subscribers when the URL changes from another useUrlState call", () => {
        const q = renderHook(() => useUrlState("q", ""));
        const page = renderHook(() => useUrlState("page", "1"));

        act(() => page.result.current[1]("5"));

        expect(q.result.current[0]).toBe("");
        expect(page.result.current[0]).toBe("5");
    });
});
