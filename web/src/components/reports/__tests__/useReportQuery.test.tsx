import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
const query = { current: "" };
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
import { useReportQuery } from "../useReportQuery";

type Q = { a: string };
const read = (sp: URLSearchParams): Q => ({ a: sp.get("a") ?? "" });
const write = (q: Q) => ({ a: q.a || undefined });

beforeEach(() => { query.current = ""; window.history.replaceState(null, "", "/en/dashboard/finance/tenant-ledger"); });

describe("useReportQuery", () => {
    it("seeds from the URL, keeps edits as a draft and writes only what is applied", () => {
        query.current = "a=1&other=keep";
        window.history.replaceState(null, "", "/en/dashboard/finance/tenant-ledger?a=1&other=keep");
        const { result } = renderHook(() => useReportQuery(read, write));
        expect(result.current.draft).toEqual({ a: "1" });
        expect(result.current.applied).toEqual({ a: "1" });

        act(() => result.current.setDraft({ a: "2" }));
        expect(result.current.draft).toEqual({ a: "2" });
        expect(result.current.applied).toEqual({ a: "1" });
        expect(window.location.search).toBe("?a=1&other=keep");

        act(() => result.current.apply());
        expect(result.current.applied).toEqual({ a: "2" });
        expect(window.location.search).toBe("?a=2&other=keep");
        expect(window.location.pathname).toBe("/en/dashboard/finance/tenant-ledger");

        act(() => result.current.apply({ a: "" }));
        expect(result.current.applied).toEqual({ a: "" });
        expect(result.current.draft).toEqual({ a: "" });
        expect(window.location.search).toBe("?other=keep");
    });

    it("removes a key written as an empty string", () => {
        window.history.replaceState(null, "", "/en/dashboard/finance/tenant-ledger?a=1&other=keep");
        const { result } = renderHook(() => useReportQuery(read, (q: Q) => ({ a: q.a })));
        act(() => result.current.apply({ a: "" }));
        expect(window.location.search).toBe("?other=keep");
    });

    it("keeps the applied query stable across re-renders until apply", () => {
        query.current = "a=9";
        const { result, rerender } = renderHook(() => useReportQuery(read, write));
        const first = result.current.applied;
        query.current = "a=10";
        rerender();
        expect(result.current.applied).toBe(first);
    });
});
