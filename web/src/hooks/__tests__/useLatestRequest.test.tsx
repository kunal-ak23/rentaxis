import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useEffect, useState } from "react";
import { useLatestRequest } from "@/hooks/useLatestRequest";
import { isAbortError } from "@/lib/api/abort";

/**
 * Break round 3, F1: a page's load effect must (a) abort its request when the
 * page unmounts (client-side navigation), silently — no console.error, no
 * state update; (b) ignore a request superseded by a newer one (a filter
 * changed quickly) so a slow old response never overwrites newer data; and
 * (c) still show the page's error state on a real failure.
 */

type Pending = { url: string; signal?: AbortSignal; resolve: (body: unknown) => void; reject: (e: unknown) => void };
let pending: Pending[];

beforeEach(() => {
    pending = [];
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
        const p: Pending = {
            url,
            signal: init?.signal ?? undefined,
            resolve: (body) => resolve(new Response(JSON.stringify(body), { status: 200 })),
            reject,
        };
        // A real fetch rejects with an AbortError the moment its signal aborts.
        init?.signal?.addEventListener("abort", () => reject(new DOMException("The operation was aborted.", "AbortError")));
        pending.push(p);
    })));
    vi.spyOn(console, "error");
});
afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

function List({ filter }: { filter: string }) {
    const begin = useLatestRequest();
    const [rows, setRows] = useState<string[] | null>(null);
    const [error, setError] = useState(false);
    useEffect(() => {
        const { signal, isCurrent } = begin();
        (async () => {
            try {
                const res = await fetch(`/api/proxy/v1/things?f=${filter}`, { signal });
                const data = (await res.json()) as string[];
                if (!isCurrent()) return;
                setRows(data);
                setError(false);
            } catch (err) {
                if (isAbortError(err) || !isCurrent()) return;
                console.error(err);
                setError(true);
            }
        })();
    }, [filter, begin]);
    if (error) return <p>load failed</p>;
    return <p>{rows ? rows.join(",") : "loading"}</p>;
}

function Harness() {
    const [filter, setFilter] = useState("a");
    const [shown, setShown] = useState(true);
    return (
        <>
            <button onClick={() => setFilter("b")}>filter-b</button>
            <button onClick={() => setShown(false)}>leave</button>
            {shown && <List filter={filter} />}
        </>
    );
}

const flush = () => act(async () => { await new Promise(r => setTimeout(r, 0)); });

describe("useLatestRequest", () => {
    it("unmount mid-fetch aborts the request: no console.error, no state update", async () => {
        render(<Harness />);
        expect(pending).toHaveLength(1);
        fireEvent.click(screen.getByText("leave"));
        await flush();
        expect(pending[0].signal?.aborted).toBe(true);
        // A response that still arrives (e.g. already in flight) is ignored too.
        pending[0].resolve(["late"]);
        await flush();
        expect(console.error).not.toHaveBeenCalled();
    });

    it("a superseded request is aborted and its slow response never overwrites the newer one", async () => {
        render(<Harness />);
        fireEvent.click(screen.getByText("filter-b"));
        expect(pending.map(p => p.url)).toEqual(["/api/proxy/v1/things?f=a", "/api/proxy/v1/things?f=b"]);
        expect(pending[0].signal?.aborted).toBe(true);
        pending[1].resolve(["b1"]);
        await flush();
        expect(screen.getByText("b1")).toBeTruthy();
        pending[0].resolve(["a-stale"]); // lands last (already aborted — a no-op on a real fetch)
        await flush();
        expect(screen.getByText("b1")).toBeTruthy();
        expect(screen.queryByText("a-stale")).toBeNull();
        expect(console.error).not.toHaveBeenCalled();
    });

    it("isCurrent guards a response that resolved before the abort could stop it", async () => {
        // A client that ignores the signal (e.g. a typed API wrapper): the stale value still loses.
        vi.stubGlobal("fetch", vi.fn((url: string) => new Promise<Response>((resolve, reject) => {
            pending.push({ url, resolve: (body) => resolve(new Response(JSON.stringify(body))), reject });
        })));
        render(<Harness />);
        fireEvent.click(screen.getByText("filter-b"));
        pending[1].resolve(["b1"]);
        await flush();
        pending[0].resolve(["a-stale"]);
        await flush();
        expect(screen.getByText("b1")).toBeTruthy();
    });

    it("a real network failure still shows the error state (and is logged)", async () => {
        render(<Harness />);
        pending[0].reject(new TypeError("Failed to fetch"));
        await flush();
        expect(screen.getByText("load failed")).toBeTruthy();
        expect(console.error).toHaveBeenCalledTimes(1);
    });
});

describe("isAbortError", () => {
    it("recognises an AbortError DOMException and an aborted-signal reason", () => {
        expect(isAbortError(new DOMException("x", "AbortError"))).toBe(true);
        const c = new AbortController();
        c.abort();
        expect(isAbortError(c.signal.reason)).toBe(true);
        expect(isAbortError(new TypeError("Failed to fetch"))).toBe(false);
        expect(isAbortError(null)).toBe(false);
        expect(isAbortError({ name: "AbortError" })).toBe(true);
    });
});
