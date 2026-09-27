import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import type { ReactNode } from "react";
import { useIdNames } from "@/hooks/useIdNames";

/**
 * The real lookupApi (chunking to 200 ids) over a stubbed fetch that answers a
 * names call with one row per requested id. The id cache is module-wide and
 * outlives a test, so every test uses its own id prefix.
 */
const fetchMock = vi.fn(async (url: string) => {
    const u = new URL(url, "http://x");
    const ids = u.searchParams.getAll("ids");
    const rows = u.pathname.endsWith("/units/names")
        ? ids.map((id) => ({ id, unitNumber: `U-${id}`, propertyId: null, propertyName: null, propertyType: null, buildingId: null, buildingName: null, status: null }))
        : ids.map((id) => ({ id, nameEn: `EN-${id}`, nameAr: id.endsWith("-noar") ? null : `AR-${id}`, phone: null, email: null }));
    return new Response(JSON.stringify(rows), { status: 200, headers: { "Content-Type": "application/json" } });
});

const idsIn = (call: unknown[]) => new URL(call[0] as string, "http://x").searchParams.getAll("ids");

function wrapper(locale: "en" | "ar") {
    return function Wrapper({ children }: { children: ReactNode }) {
        return <NextIntlClientProvider locale={locale} messages={{}}>{children}</NextIntlClientProvider>;
    };
}

beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
    cleanup();
    fetchMock.mockClear();
    vi.unstubAllGlobals();
});

describe("useIdNames", () => {
    it("fetches 450 unique ids (duplicates and nulls dropped) in 3 chunks, then labels them", async () => {
        const unique = Array.from({ length: 450 }, (_, i) => `a${i}`);
        const ids: (string | null | undefined)[] = [...unique, null, "a1", undefined, "a449", ""];
        const { result, rerender } = renderHook(({ list }) => useIdNames("units", list), {
            initialProps: { list: ids },
            wrapper: wrapper("en"),
        });

        expect(result.current.name("a1")).toBe("");
        expect(result.current.loading).toBe(true);
        await waitFor(() => expect(result.current.name("a1")).toBe("U-a1"));
        expect(result.current.loading).toBe(false);

        expect(fetchMock).toHaveBeenCalledTimes(3);
        const requested = fetchMock.mock.calls.map(idsIn);
        expect(requested.map((c) => c.length).sort((x, y) => x - y)).toEqual([50, 200, 200]);
        expect(new Set(requested.flat()).size).toBe(450);
        expect(result.current.name(null)).toBe("");
        expect(result.current.name(undefined)).toBe("");

        // Same ids in a new array (every render builds one) → no new fetch.
        rerender({ list: [...ids].reverse() });
        await Promise.resolve();
        expect(fetchMock).toHaveBeenCalledTimes(3);
    });

    it("a second instance with an overlapping set fetches only the ids not seen yet", async () => {
        const first = renderHook(() => useIdNames("units", ["b1", "b2", "b3"]), { wrapper: wrapper("en") });
        await waitFor(() => expect(first.result.current.name("b3")).toBe("U-b3"));
        expect(fetchMock).toHaveBeenCalledTimes(1);

        const second = renderHook(() => useIdNames("units", ["b2", "b3", "b4", "b5"]), { wrapper: wrapper("en") });
        // Already-known ids label on the first render.
        expect(second.result.current.name("b2")).toBe("U-b2");
        await waitFor(() => expect(second.result.current.name("b5")).toBe("U-b5"));
        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(idsIn(fetchMock.mock.calls[1])).toEqual(["b4", "b5"]);
    });

    it("concurrent mounts share one in-flight request", async () => {
        const one = renderHook(() => useIdNames("renters", ["c1", "c2"]), { wrapper: wrapper("en") });
        const two = renderHook(() => useIdNames("renters", ["c2", "c1"]), { wrapper: wrapper("en") });
        await waitFor(() => expect(two.result.current.name("c1")).not.toBe(""));
        await waitFor(() => expect(one.result.current.name("c2")).not.toBe(""));
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("labels renters like RenterPicker: 'English (Arabic)' under en, Arabic under ar", async () => {
        const en = renderHook(() => useIdNames("renters", ["d1", "d2-noar"]), { wrapper: wrapper("en") });
        await waitFor(() => expect(en.result.current.name("d1")).toBe("EN-d1 (AR-d1)"));
        expect(en.result.current.name("d2-noar")).toBe("EN-d2-noar");

        const ar = renderHook(() => useIdNames("renters", ["d1", "d2-noar"]), { wrapper: wrapper("ar") });
        expect(ar.result.current.name("d1")).toBe("AR-d1");
        expect(ar.result.current.name("d2-noar")).toBe("EN-d2-noar");
    });

    it("does not re-request ids the server did not return", async () => {
        fetchMock.mockImplementationOnce(async () => new Response("[]", { status: 200 }));
        const first = renderHook(() => useIdNames("units", ["e-gone"]), { wrapper: wrapper("en") });
        await waitFor(() => expect(first.result.current.loading).toBe(false));
        expect(first.result.current.name("e-gone")).toBe("");

        renderHook(() => useIdNames("units", ["e-gone"]), { wrapper: wrapper("en") });
        await Promise.resolve();
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("a failed call leaves names blank, stops loading, and a later mount retries", async () => {
        fetchMock.mockImplementationOnce(async () => new Response("boom", { status: 500 }));
        const first = renderHook(() => useIdNames("units", ["f1"]), { wrapper: wrapper("en") });
        await waitFor(() => expect(first.result.current.loading).toBe(false));
        expect(first.result.current.name("f1")).toBe("");

        const second = renderHook(() => useIdNames("units", ["f1"]), { wrapper: wrapper("en") });
        await waitFor(() => expect(second.result.current.name("f1")).toBe("U-f1"));
        expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it("an empty id list fetches nothing and is not loading", () => {
        const { result } = renderHook(() => useIdNames("units", [null, undefined]), { wrapper: wrapper("en") });
        expect(result.current.loading).toBe(false);
        expect(fetchMock).not.toHaveBeenCalled();
    });
});
